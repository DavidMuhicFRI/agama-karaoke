package si.davidmuhic.karaoke

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/** The "Karaoke" shortcut for AGAMA: toggles the lyrics overlay and closes immediately. */
class ToggleActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Permissions.essentials(this)) {
            KaraokeService.send(this, KaraokeService.ACTION_TOGGLE)
        } else {
            Toast.makeText(this, "Finish the setup first", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, SetupActivity::class.java))
        }
        finish()
    }
}
