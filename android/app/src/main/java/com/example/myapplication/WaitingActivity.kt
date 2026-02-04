package com.example.myapplication

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.myapplication.webrtc.SignalingHandler

class WaitingActivity : AppCompatActivity() {

    private lateinit var roomId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_waiting)



    }

    /**
     * Karşı taraf odaya girdiğinde çağrılacak
     */
    fun onPeerJoined() {
        val intent = Intent(this, RoomActivity::class.java)
        intent.putExtra("ROOM_ID", roomId)
        startActivity(intent)
        finish()
    }
}
