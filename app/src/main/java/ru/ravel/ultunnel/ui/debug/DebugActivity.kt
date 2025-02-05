package ru.ravel.ultunnel.ui.debug

import android.content.Intent
import android.os.Bundle
import ru.ravel.ultunnel.R
import ru.ravel.ultunnel.databinding.ActivityDebugBinding
import ru.ravel.ultunnel.ui.shared.AbstractActivity

class DebugActivity : AbstractActivity<ActivityDebugBinding>() {

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		setTitle(R.string.title_debug)
		binding.scanVPNButton.setOnClickListener {
			startActivity(Intent(this, VPNScanActivity::class.java))
		}
	}
}