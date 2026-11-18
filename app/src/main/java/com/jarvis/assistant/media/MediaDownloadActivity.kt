package com.jarvis.assistant.media

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.databinding.ActivityMediaDownloadBinding
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException

class MediaDownloadActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_SHARED_TEXT = "com.jarvis.assistant.media.SHARED_TEXT"
        const val EXTRA_SHARED_URL = "com.jarvis.assistant.media.SHARED_URL"
        const val EXTRA_KIND = "com.jarvis.assistant.media.KIND"
        const val EXTRA_START_DOWNLOAD = "com.jarvis.assistant.media.START_DOWNLOAD"
    }

    private lateinit var binding: ActivityMediaDownloadBinding
    private val repository by lazy { MediaDownloadRepository(applicationContext) }
    private var currentUrl: String? = null
    private var downloadedMedia: DownloadedMedia? = null
    private var pendingVoiceDownload: MediaDownloadKind? = null
    private var isDownloading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMediaDownloadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backButton.setOnClickListener { finish() }
        binding.downloadVideoButton.setOnClickListener { startDownload(MediaDownloadKind.VIDEO) }
        binding.downloadAudioButton.setOnClickListener { startDownload(MediaDownloadKind.AUDIO) }
        binding.shareMediaButton.setOnClickListener { shareDownloadedMedia() }
        binding.openSourceButton.setOnClickListener { openSourceLink() }
        binding.rightsConfirmation.setOnCheckedChangeListener { _, checked ->
            val pending = pendingVoiceDownload
            if (checked && pending != null) {
                pendingVoiceDownload = null
                startDownload(pending)
            }
        }
        binding.linkInput.doAfterTextChanged { editable ->
            val candidate = MediaDownloadPolicy.extractHttpsUrl(editable?.toString().orEmpty())
            if (candidate != currentUrl) {
                currentUrl = candidate
                binding.rightsConfirmation.isChecked = false
                pendingVoiceDownload = null
                SharedMediaDownloadSession.setSharedUrl(candidate.orEmpty())
                downloadedMedia = null
                binding.shareMediaButton.visibility = View.GONE
                binding.openSourceButton.visibility = if (candidate == null) View.GONE else View.VISIBLE
            }
        }
        applyIntent(intent, isNewSharedLink = intent.action == Intent.ACTION_SEND)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntent(intent, isNewSharedLink = intent.action == Intent.ACTION_SEND)
    }

    override fun onDestroy() {
        if (isFinishing) SharedMediaDownloadSession.clearSharedUrl()
        super.onDestroy()
    }

    private fun applyIntent(intent: Intent?, isNewSharedLink: Boolean) {
        if (intent == null) return
        val sharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ?: intent.getStringExtra(EXTRA_SHARED_TEXT)
            ?: intent.getStringExtra(EXTRA_SHARED_URL)
        val incomingUrl = intent.getStringExtra(EXTRA_SHARED_URL)
            ?.let(MediaDownloadPolicy::validateHttpsUrl)
            ?: sharedText?.let(MediaDownloadPolicy::extractHttpsUrl)
        if (!sharedText.isNullOrBlank()) {
            binding.linkInput.setText(incomingUrl ?: sharedText)
        } else if (incomingUrl != null) {
            binding.linkInput.setText(incomingUrl)
        }
        if (isNewSharedLink || incomingUrl != currentUrl) {
            binding.rightsConfirmation.isChecked = false
            pendingVoiceDownload = null
            downloadedMedia = null
            binding.shareMediaButton.visibility = View.GONE
        }
        currentUrl = incomingUrl
        SharedMediaDownloadSession.setSharedUrl(incomingUrl.orEmpty())
        binding.openSourceButton.visibility = if (incomingUrl == null) View.GONE else View.VISIBLE

        val kind = intent.getStringExtra(EXTRA_KIND)
            ?.let { value -> MediaDownloadKind.entries.firstOrNull { it.name == value } }
        if (intent.getBooleanExtra(EXTRA_START_DOWNLOAD, false) && kind != null) {
            requestVoiceDownload(kind)
        } else if (incomingUrl == null) {
            binding.statusText.text = "Share a direct HTTPS media link from another app, or paste one above."
        } else {
            binding.statusText.text = "Link received. Confirm your rights, then choose video or audio."
        }
    }

    private fun requestVoiceDownload(kind: MediaDownloadKind) {
        if (currentUrl == null) {
            binding.statusText.text = "No supported shared link is available. Share a direct HTTPS media link first."
            return
        }
        if (binding.rightsConfirmation.isChecked) {
            startDownload(kind)
        } else {
            pendingVoiceDownload = kind
            binding.statusText.text =
                "Voice request received. Confirm that you have permission to download or extract this media to continue."
            binding.rightsConfirmation.requestFocus()
        }
    }

    private fun startDownload(kind: MediaDownloadKind) {
        if (isDownloading) return
        val url = MediaDownloadPolicy.extractHttpsUrl(binding.linkInput.text?.toString().orEmpty())
        if (url == null) {
            binding.statusText.text = "Enter or share a valid public HTTPS link."
            return
        }
        currentUrl = url
        SharedMediaDownloadSession.setSharedUrl(url)
        if (!binding.rightsConfirmation.isChecked) {
            binding.statusText.text = "Please confirm you own the media or have permission to download/extract it."
            return
        }
        downloadedMedia = null
        binding.shareMediaButton.visibility = View.GONE
        binding.downloadProgress.visibility = View.VISIBLE
        binding.downloadProgress.isIndeterminate = true
        binding.statusText.text = if (kind == MediaDownloadKind.VIDEO) {
            "Checking and downloading the direct video link..."
        } else {
            "Checking and downloading/extracting audio..."
        }
        setDownloadButtonsEnabled(false)
        isDownloading = true

        lifecycleScope.launch {
            try {
                val saved = repository.download(url, kind) { downloaded, total ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (total > 0L) {
                            binding.downloadProgress.isIndeterminate = false
                            binding.downloadProgress.progress =
                                (downloaded * 100L / total).toInt().coerceIn(0, 100)
                            binding.statusText.text =
                                "Downloading ${formatSize(downloaded)} of ${formatSize(total)}..."
                        } else {
                            binding.statusText.text = "Downloading ${formatSize(downloaded)}..."
                        }
                    }
                }
                downloadedMedia = saved
                binding.statusText.text = "Saved ${saved.displayName}. Choose Share to send it using Android's share menu."
                binding.shareMediaButton.visibility = View.VISIBLE
                binding.downloadProgress.visibility = View.GONE
            } catch (error: CancellationException) {
                throw error
            } catch (error: MediaDownloadException) {
                binding.statusText.text = error.message ?: "HOPE could not download this media link."
                binding.downloadProgress.visibility = View.GONE
            } catch (error: IOException) {
                binding.statusText.text = "Network or file error. Check the link and connection, then try again."
                binding.downloadProgress.visibility = View.GONE
            } catch (error: SecurityException) {
                binding.statusText.text = "Android did not allow HOPE to save this media file."
                binding.downloadProgress.visibility = View.GONE
            } catch (error: IllegalArgumentException) {
                binding.statusText.text = "This link or media format is not supported."
                binding.downloadProgress.visibility = View.GONE
            } catch (error: IllegalStateException) {
                binding.statusText.text = "This media could not be processed on this device."
                binding.downloadProgress.visibility = View.GONE
            } finally {
                isDownloading = false
                setDownloadButtonsEnabled(true)
            }
        }
    }

    private fun setDownloadButtonsEnabled(enabled: Boolean) {
        binding.downloadVideoButton.isEnabled = enabled
        binding.downloadAudioButton.isEnabled = enabled
    }

    private fun shareDownloadedMedia() {
        val media = downloadedMedia ?: return
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = media.mimeType
            putExtra(Intent.EXTRA_STREAM, media.uri)
            clipData = android.content.ClipData.newUri(contentResolver, media.displayName, media.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(sendIntent, "Share media"))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No app is available to share this media.", Toast.LENGTH_LONG).show()
        }
    }

    private fun openSourceLink() {
        val url = currentUrl ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No browser is available to open this link.", Toast.LENGTH_LONG).show()
        }
    }

    private fun formatSize(bytes: Long): String {
        val mebibytes = bytes.toDouble() / (1024.0 * 1024.0)
        return String.format(java.util.Locale.ROOT, "%.1f MB", mebibytes)
    }
}
