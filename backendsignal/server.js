const express = require("express");
const http = require("http");
const { Server } = require("socket.io");
const cors = require("cors");
const ip = require("ip");
const mediasoup = require("mediasoup");

const app = express();
app.use(cors());

const server = http.createServer(app);
const io = new Server(server, {
  cors: { origin: "*", methods: ["GET", "POST"] },
  pingTimeout: 60000,
  pingInterval: 25000,
});

// ─────────────────────────────
//  MEDIASOUP CONFIGÜRASYONLARı
// ─────────────────────────────
const mediasoupConfig = {
  worker: {
    rtcMinPort: 10000,
    rtcMaxPort: 10100,
    logLevel: "warn",
    logTags: ["info", "ice", "dtls", "rtp", "srtp", "rtcp"],
  },
  router: {
    mediaCodecs: [
      {
        kind: "audio",
        mimeType: "audio/opus",
        clockRate: 48000,
        channels: 2,
      },
      {
        kind: "video",
        mimeType: "video/VP8",
        clockRate: 90000,
        parameters: { "x-google-start-bitrate": 1000 },
      },
      {
        kind: "video",
        mimeType: "video/H264",
        clockRate: 90000,
        parameters: {
          "packetization-mode": 1,
          "profile-level-id": "42e01f",
          "level-asymmetry-allowed": 1,
        },
      },
    ],
  },
  webRtcTransport: {
    listenIps: [{ ip: "0.0.0.0", announcedIp: null }], // announcedIp sunucu başlarken ayarlanacak
    enableUdp: true,
    enableTcp: true,
    preferUdp: true,
    initialAvailableOutgoingBitrate: 1000000,
  },
};

// ─────────────────────────────
//  GLOBAL STATE
// ─────────────────────────────
let worker;
let router;

// Oda yapısı: rooms[roomId] = { users: [...], producers: { [socketId]: { audio: Producer, video: Producer } }, transports: { [socketId]: { send: Transport, recv: Transport } }, consumers: { [socketId]: [...Consumer] } }
const rooms = {};

// ─────────────────────────────
//  MEDIASOUP BAŞLATMA
// ─────────────────────────────
async function startMediasoup() {
  worker = await mediasoup.createWorker(mediasoupConfig.worker);
  console.log("Mediasoup Worker PID:", worker.pid);

  worker.on("died", () => {
    console.error("Mediasoup Worker öldü! Sunucu yeniden başlatılıyor...");
    process.exit(1);
  });

  router = await worker.createRouter({
    mediaCodecs: mediasoupConfig.router.mediaCodecs,
  });
  console.log("Mediasoup Router oluşturuldu.");

  // AnnouncedIp ayarla
  const ipAddress = ip.address();
  mediasoupConfig.webRtcTransport.listenIps[0].announcedIp = ipAddress;
}

// ─────────────────────────────
//  TRANSPORT OLUŞTURMA YARDIMCISI
// ─────────────────────────────
async function createWebRtcTransport() {
  const transport = await router.createWebRtcTransport(
    mediasoupConfig.webRtcTransport
  );

  transport.on("dtlsstatechange", (dtlsState) => {
    if (dtlsState === "closed") {
      transport.close();
    }
  });

  return {
    transport,
    params: {
      id: transport.id,
      iceParameters: transport.iceParameters,
      iceCandidates: transport.iceCandidates,
      dtlsParameters: transport.dtlsParameters,
    },
  };
}

// ─────────────────────────────
//  ODA YÖNETİMİ YARDIMCILARI
// ─────────────────────────────
function getOrCreateRoom(roomId) {
  if (!rooms[roomId]) {
    rooms[roomId] = {
      users: [],
      producers: {},
      transports: {},
      consumers: {},
    };
  }
  return rooms[roomId];
}

function cleanupUser(socket) {
  let roomIdToDelete = null;

  for (const roomId in rooms) {
    const room = rooms[roomId];
    const userIndex = room.users.findIndex((u) => u.socketId === socket.id);

    if (userIndex !== -1) {
      const leaver = room.users[userIndex];
      console.log(`${leaver.username} (${socket.id}) ayrıldı.`);

      // Transport'ları kapat
      if (room.transports[socket.id]) {
        if (room.transports[socket.id].send) {
          room.transports[socket.id].send.close();
        }
        if (room.transports[socket.id].recv) {
          room.transports[socket.id].recv.close();
        }
        delete room.transports[socket.id];
      }

      // Producer'ları kapat
      if (room.producers[socket.id]) {
        Object.values(room.producers[socket.id]).forEach((producer) => {
          if (producer) producer.close();
        });
        delete room.producers[socket.id];
      }

      // Consumer'ları kapat
      if (room.consumers[socket.id]) {
        room.consumers[socket.id].forEach((consumer) => {
          if (consumer) consumer.close();
        });
        delete room.consumers[socket.id];
      }

      // Odada kalan diğer kişiye haber ver
      socket.to(roomId).emit("user-disconnected", {
        socketId: socket.id,
        username: leaver.username,
      });

      // Kullanıcıyı listeden sil
      room.users.splice(userIndex, 1);

      if (room.users.length === 0) {
        roomIdToDelete = roomId;
      } else {
        io.to(roomId).emit("room-users", room.users);
      }
      break;
    }
  }

  if (roomIdToDelete) {
    delete rooms[roomIdToDelete];
    console.log(`Oda ${roomIdToDelete} boş olduğu için silindi.`);
  }
}

// ─────────────────────────────
//  SOCKET.IO EVENT'LERİ
// ─────────────────────────────
io.on("connection", (socket) => {
  console.log("Yeni bağlantı (Socket ID):", socket.id);

  // ═══════════════════════════
  //  ODA YÖNETİMİ (MEVCUT MANTIK KORUNDU)
  // ═══════════════════════════

  socket.on("join-room", ({ roomId, username }) => {
    const room = getOrCreateRoom(roomId);

    // 1. Oda hiç yoksa veya boşsa - Host olarak gir
    if (room.users.length === 0) {
      const user = { socketId: socket.id, username, isHost: true };
      room.users.push(user);
      socket.join(roomId);
      socket.emit("room-joined", {
        status: "approved",
        isHost: true,
        users: room.users,
      });
      console.log(`Oda oluşturuldu: ${roomId}, Host: ${username}`);
      return;
    }

    // 2. Oda var, içeride Host var mı kontrol et
    const hostUser = room.users.find((user) => user.isHost);

    if (hostUser) {
      // Host'a bildirim gönder
      io.to(hostUser.socketId).emit("join-request", {
        socketId: socket.id,
        username,
      });
      socket.emit("waiting-approval");
      console.log(
        `${username} bekleme odasına alındı (Host onayı bekleniyor).`
      );
    } else {
      // Odada Host yoksa gelen kişiyi Host yap
      const user = { socketId: socket.id, username, isHost: true };
      room.users.push(user);
      socket.join(roomId);
      socket.emit("room-joined", {
        status: "approved",
        isHost: true,
        users: room.users,
      });
    }
  });

  socket.on("handle-join-request", ({ decision, requesterId, requesterName }) => {
    const roomEntries = Object.entries(rooms).find(([id, room]) =>
      room.users.some((u) => u.socketId === socket.id && u.isHost)
    );

    if (!roomEntries) {
      console.log("Yetkisiz işlem denemesi veya oda bulunamadı.");
      return;
    }

    const [roomId, room] = roomEntries;

    if (decision === "approve") {
      room.users.push({
        socketId: requesterId,
        username: requesterName,
        isHost: false,
      });

      const requesterSocket = io.sockets.sockets.get(requesterId);
      if (requesterSocket) {
        requesterSocket.join(roomId);
        requesterSocket.emit("room-joined", {
          status: "approved",
          isHost: false,
          users: room.users,
        });
      }

      io.to(roomId).emit("room-users", room.users);
      console.log(`${requesterName} odaya kabul edildi.`);
    } else {
      const requesterSocket = io.sockets.sockets.get(requesterId);
      if (requesterSocket) {
        requesterSocket.emit("join-rejected");
        console.log(`${requesterName} reddedildi.`);
      }
    }
  });

  // ═══════════════════════════
  //  MEDIASOUP SİNYALLEŞME
  // ═══════════════════════════

  // 1. Client RTP yeteneklerini ister
  socket.on("get-rtp-capabilities", (callback) => {
    try {
      callback({ rtpCapabilities: router.rtpCapabilities });
    } catch (err) {
      console.error("get-rtp-capabilities hatası:", err);
      callback({ error: err.message });
    }
  });

  // 2. Client transport oluşturma ister (send veya recv)
  socket.on("create-transport", async ({ roomId, direction }, callback) => {
    try {
      const room = getOrCreateRoom(roomId);
      const { transport, params } = await createWebRtcTransport();

      if (!room.transports[socket.id]) {
        room.transports[socket.id] = {};
      }

      room.transports[socket.id][direction] = transport;

      console.log(
        `Transport oluşturuldu: ${socket.id} / ${direction} / ${transport.id}`
      );
      callback(params);
    } catch (err) {
      console.error("create-transport hatası:", err);
      callback({ error: err.message });
    }
  });

  // 3. Client transport'u bağlar (DTLS handshake)
  socket.on(
    "connect-transport",
    async ({ roomId, direction, dtlsParameters }, callback) => {
      try {
        const room = rooms[roomId];
        if (!room || !room.transports[socket.id]) {
          throw new Error("Transport bulunamadı");
        }

        const transport = room.transports[socket.id][direction];
        if (!transport) {
          throw new Error(`${direction} transport bulunamadı`);
        }

        await transport.connect({ dtlsParameters });
        console.log(`Transport bağlandı: ${socket.id} / ${direction}`);
        callback({ connected: true });
      } catch (err) {
        console.error("connect-transport hatası:", err);
        callback({ error: err.message });
      }
    }
  );

  // 4. Client medya akışı gönderir (produce)
  socket.on(
    "produce",
    async ({ roomId, kind, rtpParameters, appData }, callback) => {
      try {
        const room = rooms[roomId];
        if (!room || !room.transports[socket.id]) {
          throw new Error("Send transport bulunamadı");
        }

        const transport = room.transports[socket.id].send;
        if (!transport) {
          throw new Error("Send transport bulunamadı");
        }

        const producer = await transport.produce({
          kind,
          rtpParameters,
          appData,
        });

        if (!room.producers[socket.id]) {
          room.producers[socket.id] = {};
        }
        room.producers[socket.id][kind] = producer;

        console.log(
          `Producer oluşturuldu: ${socket.id} / ${kind} / ${producer.id}`
        );

        // Odadaki diğer kullanıcılara bu yeni producer'ı bildir
        socket.to(roomId).emit("new-producer", {
          producerId: producer.id,
          producerSocketId: socket.id,
          kind,
        });

        producer.on("transportclose", () => {
          console.log(`Producer transport kapatıldı: ${producer.id}`);
          producer.close();
        });

        callback({ producerId: producer.id });
      } catch (err) {
        console.error("produce hatası:", err);
        callback({ error: err.message });
      }
    }
  );

  // 5. Client karşı tarafın medyasını almak ister (consume)
  socket.on(
    "consume",
    async ({ roomId, producerId, rtpCapabilities }, callback) => {
      try {
        if (!router.canConsume({ producerId, rtpCapabilities })) {
          throw new Error("Consume edilemiyor (codec uyumsuz)");
        }

        const room = rooms[roomId];
        if (!room || !room.transports[socket.id]) {
          throw new Error("Recv transport bulunamadı");
        }

        const transport = room.transports[socket.id].recv;
        if (!transport) {
          throw new Error("Recv transport bulunamadı");
        }

        const consumer = await transport.consume({
          producerId,
          rtpCapabilities,
          paused: true,
        });

        if (!room.consumers[socket.id]) {
          room.consumers[socket.id] = [];
        }
        room.consumers[socket.id].push(consumer);

        consumer.on("transportclose", () => {
          console.log(`Consumer transport kapatıldı: ${consumer.id}`);
        });

        consumer.on("producerclose", () => {
          console.log(`Producer kapatıldı, consumer kapatılıyor: ${consumer.id}`);
          consumer.close();
          if (room.consumers[socket.id]) {
            room.consumers[socket.id] = room.consumers[socket.id].filter(
              (c) => c.id !== consumer.id
            );
          }
          socket.emit("producer-closed", { producerId });
        });

        console.log(
          `Consumer oluşturuldu: ${socket.id} / ${consumer.kind} / ${consumer.id}`
        );

        callback({
          consumerId: consumer.id,
          producerId,
          kind: consumer.kind,
          rtpParameters: consumer.rtpParameters,
        });
      } catch (err) {
        console.error("consume hatası:", err);
        callback({ error: err.message });
      }
    }
  );

  // 6. Client consumer'ı devam ettirir (resume)
  socket.on("consumer-resume", async ({ roomId, consumerId }, callback) => {
    try {
      const room = rooms[roomId];
      if (!room || !room.consumers[socket.id]) {
        throw new Error("Consumer bulunamadı");
      }

      const consumer = room.consumers[socket.id].find(
        (c) => c.id === consumerId
      );
      if (!consumer) {
        throw new Error("Consumer bulunamadı");
      }

      await consumer.resume();
      console.log(`Consumer resume edildi: ${consumerId}`);
      callback({ resumed: true });
    } catch (err) {
      console.error("consumer-resume hatası:", err);
      callback({ error: err.message });
    }
  });

  // 7. Client mevcut producer'ları ister
  socket.on("get-producers", ({ roomId }, callback) => {
    try {
      const room = rooms[roomId];
      if (!room) {
        callback({ producers: [] });
        return;
      }

      const producers = [];
      for (const [socketId, producerMap] of Object.entries(room.producers)) {
        if (socketId === socket.id) continue; // Kendi producer'larımı hariç tut

        for (const [kind, producer] of Object.entries(producerMap)) {
          if (producer && !producer.closed) {
            producers.push({
              producerId: producer.id,
              producerSocketId: socketId,
              kind,
            });
          }
        }
      }

      callback({ producers });
    } catch (err) {
      console.error("get-producers hatası:", err);
      callback({ error: err.message });
    }
  });

  // ═══════════════════════════
  //  BAĞLANTI KOPMA
  // ═══════════════════════════

  socket.on("leave-room", () => {
    cleanupUser(socket);
  });

  socket.on("disconnect", () => {
    cleanupUser(socket);
  });
});

// ─────────────────────────────
//  SUNUCU BAŞLATMA
// ─────────────────────────────
const PORT = process.env.PORT || 5006;
const ipAddress = ip.address();

startMediasoup().then(() => {
  server.listen(PORT, () => {
    console.log(`Server running on port ${PORT}`);
    console.log(`Mobil bağlantı için adres: http://${ipAddress}:${PORT}`);
  });
});