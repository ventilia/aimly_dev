package io.getaimly.backend.bot
import org.springframework.core.io.Resource
import org.springframework.web.multipart.MultipartFile
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

class ByteArrayMultipartFile(
    private val bytes: ByteArray,
    override val name: String,
    override val originalFilename: String,
    override val contentType: String?
) : MultipartFile {
    override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)
    override fun getSize(): Long = bytes.size.toLong()
    override fun isEmpty(): Boolean = bytes.isEmpty()
    override fun getBytes(): ByteArray = bytes
    override fun transferTo(dest: File) { dest.writeBytes(bytes) }
    override fun getResource(): Resource = throw UnsupportedOperationException("getResource not supported")
}