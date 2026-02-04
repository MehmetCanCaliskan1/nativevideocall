/* const express=require('express');
const app=express();
const port=3000;

const ip = require('ip');
const http=require('http');
const server=http.createServer(app);

const io=require('socket.io')(server,{
    cors:{
        origin:"*",
        methods:["GET","POST"]
    }
});
let rooms={};
const ipAdress=ip.address();

app.get('/',(req,res)=>{
    res.send('Signal Server is running');
});

io.on("error",(err)=>{
    console.log("Error: ",err);
}); 

io.on('connection',(socket)=>{
    console.log('New user connected: ',socket.id);
    socket.on('join-room',(roomId,userId)=>{
        const roomId */




        const express = require("express");
const http = require("http");
const { Server } = require("socket.io");
const cors = require("cors");
const ip = require("ip"); 

const app = express();
app.use(cors());

const rooms = {};

const server = http.createServer(app);

const io = new Server(server, {
  cors: {
    origin: "*", 
    methods: ["GET", "POST"],
  },
  pingTimeout: 60000,
  pingInterval: 25000
});

io.on("connection", (socket) => {
  console.log("Yeni bağlantı (Socket ID):", socket.id);

  socket.on("join-room", ({ roomId, username }) => {
    // 1. Oda hiç yoksa oluştur 
    if (!rooms[roomId] || rooms[roomId].length === 0) {
      rooms[roomId] = [];
      
      const user = { socketId: socket.id, username, isHost: true };
      rooms[roomId].push(user);
      
      socket.join(roomId);
      
      // Kullanıcıya Host olduğunu bildir
      socket.emit("room-joined", { status: "approved", isHost: true, users: rooms[roomId] });
      console.log(`Oda oluşturuldu: ${roomId}, Host: ${username}`);
      return;
    }

    // 2. Oda var, içeride Host var mı kontrol et.
    const hostUser = rooms[roomId].find(user => user.isHost);
    
    if (hostUser) {
      // Host'a bildirim gönder: "Bu kişi girmek istiyor"
      io.to(hostUser.socketId).emit("join-request", { 
        socketId: socket.id, 
        username 
      });
      
      // Misafire bildirim gönderme kısmı
      socket.emit("waiting-approval");
      console.log(`${username} bekleme odasına alındı (Host onayı bekleniyor).`);
    } else {
      // Eğer odada kimse kalmadıysa veya Host düşmüşse ama oda silinmemişse (Fail-safe)
      // Gelen kişiyi odaya al ve Host yap
      const user = { socketId: socket.id, username, isHost: true }; 
      rooms[roomId].push(user);
      socket.join(roomId);
      socket.emit("room-joined", { status: "approved", isHost: true, users: rooms[roomId] });
    }
  });

  socket.on("handle-join-request", ({ decision, requesterId, requesterName }) => {
    // decision: 'approve' veya 'reject'
    
    // İşlemi yapan gerçekten o odanın Host'u mu
    const roomEntries = Object.entries(rooms).find(([id, users]) => 
      users.some(u => u.socketId === socket.id && u.isHost)
    );

    if (!roomEntries) {
      console.log("Yetkisiz işlem denemesi veya oda bulunamadı.");
      return; 
    }
    
    const [roomId, users] = roomEntries;

    if (decision === "approve") {
      // 1. Kullanıcıyı listeye ekle
      rooms[roomId].push({ 
        socketId: requesterId, 
        username: requesterName, 
        isHost: false 
      });

      // 2. Bekleyen soketi odaya dahil et
      const requesterSocket = io.sockets.sockets.get(requesterId);
      if (requesterSocket) {
        requesterSocket.join(roomId);
        
        // 3. Bekleyen kişiye "Girebilirsin" de
        requesterSocket.emit("room-joined", { 
          status: "approved", 
          isHost: false, 
          users: rooms[roomId] 
        });
      }

      // 4. Odadaki herkese güncel listeyi at
      io.to(roomId).emit("room-users", rooms[roomId]);
      console.log(`${requesterName} odaya kabul edildi.`);

    } else {
      // REDDEDİLME DURUMU
      const requesterSocket = io.sockets.sockets.get(requesterId);
      if (requesterSocket) {
        requesterSocket.emit("join-rejected");
        console.log(`${requesterName} reddedildi.`);
      }
    }
  });

  // WEBRTC SIGNALING 
  
  socket.on("webrtc-offer", ({ to, offer }) => {
    // 'to': Karşı tarafın socketId'si
    console.log(`Offer gönderiliyor: ${socket.id} -> ${to}`);
    io.to(to).emit("webrtc-offer", {
      from: socket.id,
      offer
    });
  });

  socket.on("webrtc-answer", ({ to, answer }) => {
    console.log(`Answer gönderiliyor: ${socket.id} -> ${to}`);
    io.to(to).emit("webrtc-answer", {
      from: socket.id,
      answer
    });
  });

  socket.on("webrtc-ice", ({ to, candidate }) => {
    // ICE Candidate'ler çok sık gelir, loglamayı kapalı tutabilirsin.
    io.to(to).emit("webrtc-ice", {
      from: socket.id,
      candidate
    });
  });

  // MANUEL AYRILMA (Mobilde "Kapat" butonu için)
  socket.on("leave-room", () => {
    handleDisconnect(socket);
  });

  socket.on("disconnect", () => {
    handleDisconnect(socket);
  });
});

// Kullanıcı odadan çıktığında yapılacak temizlik işlemleri
function handleDisconnect(socket) {
  let roomIdToDelete = null;

  for (const roomId in rooms) {
    const userIndex = rooms[roomId].findIndex(user => user.socketId === socket.id);
    
    if (userIndex !== -1) {
      const leaver = rooms[roomId][userIndex];
      console.log(`${leaver.username} (${socket.id}) ayrıldı.`);

      // Kullanıcıyı listeden sil
      rooms[roomId].splice(userIndex, 1);

   
      //  Sadece listeden siler, kalanlar devam eder.
      
      if (rooms[roomId].length === 0) {
        roomIdToDelete = roomId;
      } else {
        // Kalanlara güncel listeyi gönder
        io.to(roomId).emit("room-users", rooms[roomId]);
      }
      break; 
    }
  }

  if (roomIdToDelete) {
    delete rooms[roomIdToDelete];
    console.log(`Oda ${roomIdToDelete} boş olduğu için silindi.`);
  }
}

const PORT = process.env.PORT || 5006;
const ipAddress = ip.address(); //local ağ ip adresinin alınması kısmı

server.listen(PORT, () => {
  console.log(`Server running on port ${PORT}`);
  console.log(`Mobil bağlantı için adres: http://${ipAddress}:${PORT}`);
});