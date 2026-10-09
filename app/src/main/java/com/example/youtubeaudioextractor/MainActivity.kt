package com.example.youtubeaudioextractor

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
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

    companion object {
        private const val MUSIC_FOLDER_NAME = "유튜브 음원추출"
        private const val AUDIO_MIME_TYPE = "audio/mpeg"
    }

    private lateinit var etYoutubeUrl: EditText
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etYoutubeUrl = findViewById(R.id.etYoutubeUrl)
        val btnDownload = findViewById<Button>(R.id.btnDownload)
        val progressBar = findViewById<ProgressBar>(R.id.progressBar)
        tvStatus = findViewById(R.id.tvStatus)
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

            val publicMusicDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            val appMusicDir = File(publicMusicDir, MUSIC_FOLDER_NAME)

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                !appMusicDir.exists() &&
                !appMusicDir.mkdirs()
            ) {
                Toast.makeText(this, "음악 폴더를 만들 수 없습니다.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            btnDownload.isEnabled = false
            btnOpenFolder.visibility = View.GONE
            progressBar.progress = 0
            tvStatus.text = "추출 준비 중..."

            CoroutineScope(Dispatchers.IO).launch {
                val workingDir = File(
                    cacheDir,
                    "youtube-audio/${System.currentTimeMillis()}"
                )

                try {
                    if (!workingDir.mkdirs()) {
                        throw IllegalStateException("임시 작업 폴더를 만들 수 없습니다.")
                    }

                    val request = YoutubeDLRequest(url).apply {
                        addOption("-f", "bestaudio")
                        addOption("--extract-audio")
                        addOption("--audio-format", "mp3")
                        addOption("-o", "${workingDir.absolutePath}/%(title)s.%(ext)s")
                    }

                    YoutubeDL.getInstance().execute(request) { progress, _, _ ->
                        CoroutineScope(Dispatchers.Main).launch {
                            progressBar.progress = progress.toInt()
                            tvStatus.text = "진행률: ${progress.toInt()}%"
                        }
                    }

                    val extractedMp3Files = workingDir.listFiles()
                        ?.filter { it.isFile && it.extension.equals("mp3", ignoreCase = true) }
                        .orEmpty()

                    if (extractedMp3Files.isEmpty()) {
                        throw IllegalStateException("추출된 MP3 파일을 찾을 수 없습니다.")
                    }

                    withContext(Dispatchers.Main) {
                        tvStatus.text = "다운로드 완료! 삼성 음악에 등록 중..."
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        extractedMp3Files.forEach { sourceFile ->
                            publishToMediaStore(sourceFile)
                        }
                    } else {
                        val publishedFiles = extractedMp3Files.map { sourceFile ->
                            publishLegacyAudio(sourceFile, appMusicDir)
                        }
                        scanAudioFiles(publishedFiles)
                    }

                    withContext(Dispatchers.Main) {
                        progressBar.progress = 100
                        tvStatus.text = buildString {
                            append("다운로드 완료!\n")
                            append("삼성 음악 라이브러리 등록 완료\n")
                            append("저장 경로: Music/$MUSIC_FOLDER_NAME")
                        }
                        btnDownload.isEnabled = true
                        btnOpenFolder.visibility = View.VISIBLE
                        configureOpenFolderButton(btnOpenFolder, appMusicDir)
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "MP3 download failed", e)
                    withContext(Dispatchers.Main) {
                        tvStatus.text = "오류 발생: ${e.message}"
                        btnDownload.isEnabled = true
                    }
                } finally {
                    workingDir.deleteRecursively()
                }
            }
        }

        handleSharedIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedIntent(intent)
    }

    private fun handleSharedIntent(sharedIntent: Intent?) {
        if (sharedIntent?.action != Intent.ACTION_SEND || sharedIntent.type != "text/plain") {
            return
        }

        val sharedText = sharedIntent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (sharedText.isBlank()) return

        val youtubeUrl = extractYoutubeUrl(sharedText) ?: return
        etYoutubeUrl.setText(youtubeUrl)
        etYoutubeUrl.setSelection(youtubeUrl.length)
        tvStatus.text = "유튜브에서 공유한 링크를 받았습니다."
    }

    private fun extractYoutubeUrl(text: String): String? {
        val youtubeUrlRegex = Regex(
            """https?://(?:(?:[A-Za-z0-9-]+\.)?youtube\.com/\S+|youtu\.be/\S+)""",
            RegexOption.IGNORE_CASE
        )

        return youtubeUrlRegex.find(text)?.value?.trimEnd('.', ',', ')', ']', '}')
    }

    /**
     * Android 10+에서는 MP3를 MediaStore.Audio 컬렉션에 직접 게시한다.
     * 실제 파일 위치는 Music/유튜브 음원추출/파일명.mp3 이다.
     *
     * IS_MUSIC=1로 등록하고 Music 컬렉션 경로에 저장해서
     * Samsung Music 같은 음악 앱이 재부팅 없이 즉시 인식하기 쉽게 한다.
     */
    private fun publishToMediaStore(sourceFile: File): Uri {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)

        val resolver = applicationContext.contentResolver
        val audioCollection = MediaStore.Audio.Media.getContentUri(
            MediaStore.VOLUME_EXTERNAL_PRIMARY
        )

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, sourceFile.name)
            put(MediaStore.Audio.Media.TITLE, sourceFile.nameWithoutExtension)
            put(MediaStore.Audio.Media.MIME_TYPE, AUDIO_MIME_TYPE)
            put(
                MediaStore.Audio.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_MUSIC}/$MUSIC_FOLDER_NAME"
            )
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val audioUri = resolver.insert(audioCollection, values)
            ?: throw IllegalStateException("MediaStore에 MP3 항목을 만들 수 없습니다.")

        try {
            resolver.openOutputStream(audioUri, "w")?.use { output ->
                sourceFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: throw IllegalStateException("MediaStore MP3 파일을 열 수 없습니다.")

            val completedValues = ContentValues().apply {
                put(MediaStore.Audio.Media.IS_PENDING, 0)
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
            }
            resolver.update(audioUri, completedValues, null, null)
            resolver.notifyChange(audioUri, null)
            resolver.notifyChange(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, null)

            Log.d("MediaStore", "Published audio: ${sourceFile.name} -> $audioUri")
            return audioUri
        } catch (e: Exception) {
            resolver.delete(audioUri, null, null)
            throw e
        }
    }

    private fun publishLegacyAudio(sourceFile: File, directory: File): File {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("음악 폴더를 만들 수 없습니다.")
        }

        val destination = File(directory, sourceFile.name)
        sourceFile.copyTo(destination, overwrite = true)
        return destination
    }

    private suspend fun scanAudioFiles(files: List<File>) {
        if (files.isEmpty()) return

        suspendCancellableCoroutine { continuation ->
            val remaining = AtomicInteger(files.size)
            val paths = files.map { it.absolutePath }.toTypedArray()
            val mimeTypes = Array(files.size) { AUDIO_MIME_TYPE }

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
