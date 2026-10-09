package com.example.youtubeaudioextractor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val etYoutubeUrl = findViewById<EditText>(R.id.etYoutubeUrl)
        val btnDownload = findViewById<Button>(R.id.btnDownload)
        val progressBar = findViewById<ProgressBar>(R.id.progressBar)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val btnOpenFolder = findViewById<Button>(R.id.btnOpenFolder)

        btnDownload.setOnClickListener {
            val url = etYoutubeUrl.text.toString().trim()
            if (url.isBlank()) {
                Toast.makeText(this, "링크를 입력해주세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    1001
                )
                return@setOnClickListener
            }

            val publicDownloadDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val appDownloadDir = File(publicDownloadDir, "유튜브 음원추출")

            if (!appDownloadDir.exists() && !appDownloadDir.mkdirs()) {
                Toast.makeText(this, "다운로드 폴더를 만들 수 없습니다.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            btnDownload.isEnabled = false
            btnOpenFolder.visibility = View.GONE
            progressBar.progress = 0
            tvStatus.text = "추출 준비 중..."

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val beforeDownload = snapshotMp3Files(appDownloadDir)

                    val request = YoutubeDLRequest(url).apply {
                        addOption("-f", "bestaudio")
                        addOption("--extract-audio")
                        addOption("--audio-format", "mp3")
                        addOption("-o", "${appDownloadDir.absolutePath}/%(title)s.%(ext)s")
                    }

                    YoutubeDL.getInstance().execute(request) { progress, _, _ ->
                        CoroutineScope(Dispatchers.Main).launch {
                            progressBar.progress = progress.toInt()
                            tvStatus.text = "진행률: ${progress.toInt()}%"
                        }
                    }

                    val changedMp3Files = findChangedMp3Files(appDownloadDir, beforeDownload)

                    withContext(Dispatchers.Main) {
                        tvStatus.text = if (changedMp3Files.isEmpty()) {
                            "다운로드 완료! 미디어 라이브러리에서 파일을 확인 중..."
                        } else {
                            "다운로드 완료! 삼성 음악 목록을 갱신 중..."
                        }
                    }

                    scanAudioFiles(changedMp3Files)

                    withContext(Dispatchers.Main) {
                        progressBar.progress = 100
                        tvStatus.text = buildString {
                            append("다운로드 완료!\n")
                            if (changedMp3Files.isNotEmpty()) {
                                append("삼성 음악 라이브러리 갱신 완료\n")
                            }
                            append("저장 경로: ${appDownloadDir.absolutePath}")
                        }
                        btnDownload.isEnabled = true
                        btnOpenFolder.visibility = View.VISIBLE
                        configureOpenFolderButton(btnOpenFolder, appDownloadDir)
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "MP3 download failed", e)
                    withContext(Dispatchers.Main) {
                        tvStatus.text = "오류 발생: ${e.message}"
                        btnDownload.isEnabled = true
                    }
                }
            }
        }
    }

    private fun snapshotMp3Files(directory: File): Map<String, Long> =
        directory.listFiles()
            ?.filter { it.isFile && it.extension.equals("mp3", ignoreCase = true) }
            ?.associate { it.absolutePath to it.lastModified() }
            .orEmpty()

    private fun findChangedMp3Files(
        directory: File,
        beforeDownload: Map<String, Long>
    ): List<File> =
        directory.listFiles()
            ?.filter { file ->
                file.isFile &&
                    file.extension.equals("mp3", ignoreCase = true) &&
                    beforeDownload[file.absolutePath] != file.lastModified()
            }
            .orEmpty()

    private suspend fun scanAudioFiles(files: List<File>) {
        if (files.isEmpty()) return

        suspendCancellableCoroutine { continuation ->
            val remaining = AtomicInteger(files.size)
            val paths = files.map { it.absolutePath }.toTypedArray()
            val mimeTypes = Array(files.size) { "audio/mpeg" }

            MediaScannerConnection.scanFile(
                applicationContext,
                paths,
                mimeTypes
            ) { path, uri ->
                Log.d("MediaScanner", "Indexed: $path -> $uri")
                if (remaining.decrementAndGet() == 0 && continuation.isActive) {
                    continuation.resume(Unit)
                }
            }
        }
    }

    private fun configureOpenFolderButton(button: Button, directory: File) {
        button.setOnClickListener {
            val uri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                directory
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "vnd.android.document/directory")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            try {
                startActivity(Intent.createChooser(intent, "폴더를 열 앱을 선택하세요"))
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    "폴더를 열 수 있는 앱이 없습니다. '내 파일' 앱에서 확인해주세요.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
