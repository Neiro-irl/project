package com.neiro.aftertone.storage

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class LocalLibraryRepositoryRecoveryMutationTest {
    @Test
    fun libraryMutationDoesNotOverwriteStateWhenJournalRecoveryIsPending() {
        val dataDirectory = Files.createTempDirectory("aftertone-recovery-mutation").toFile()
        val filesDirectory = File(dataDirectory, "files").apply { mkdirs() }
        val cacheDirectory = File(dataDirectory, "cache")
        val preferences = InMemorySharedPreferences()
        val repository = LocalLibraryRepository(
            RepositoryTestContext(dataDirectory, filesDirectory, cacheDirectory, preferences.value),
        )

        try {
            repository.addPlaylist("Before recovery")
            File(filesDirectory, ".aftertone-restore-journal").writeBytes(byteArrayOf(0x00))

            var mutationFailure: IOException? = null
            try {
                repository.addPlaylist("Must not be persisted")
            } catch (error: IOException) {
                mutationFailure = error
            }

            assertNotNull("The pending restore journal must reject a library mutation.", mutationFailure)
            assertEquals(listOf("Before recovery"), repository.load().playlists.map { it.name })
            assertEquals(
                RestoreActionGate.RECOVERY_PENDING_ID,
                repository.restoreActionGate.pendingCleanupTransactionId,
            )
        } finally {
            dataDirectory.deleteRecursively()
        }
    }
}

private class RepositoryTestContext(
    private val dataDirectory: File,
    private val filesDirectory: File,
    private val cacheDirectory: File,
    private val preferences: SharedPreferences,
) : ContextWrapper(null) {
    override fun getApplicationContext(): Context = this
    override fun getDataDir(): File = dataDirectory
    override fun getFilesDir(): File = filesDirectory
    override fun getCacheDir(): File = cacheDirectory
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
    override fun getSystemService(name: String): Any? = null
}

private class InMemorySharedPreferences : InvocationHandler {
    private val values = mutableMapOf<String, Any?>()
    val value: SharedPreferences = proxy(SharedPreferences::class.java, this)

    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        val arguments = args.orEmpty()
        return when (method.name) {
            "getAll" -> values.toMap()
            "getString" -> values[arguments[0] as String] as? String ?: arguments[1]
            "getStringSet" -> arguments[1]
            "getInt" -> values[arguments[0] as String] as? Int ?: arguments[1]
            "getLong" -> values[arguments[0] as String] as? Long ?: arguments[1]
            "getFloat" -> values[arguments[0] as String] as? Float ?: arguments[1]
            "getBoolean" -> values[arguments[0] as String] as? Boolean ?: arguments[1]
            "contains" -> values.containsKey(arguments[0] as String)
            "edit" -> newEditor()
            "registerOnSharedPreferenceChangeListener", "unregisterOnSharedPreferenceChangeListener" -> null
            "toString" -> "InMemorySharedPreferences"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === arguments.firstOrNull()
            else -> error("Unexpected SharedPreferences call: ${method.name}")
        }
    }

    private fun newEditor(): SharedPreferences.Editor {
        val changedValues = linkedMapOf<String, Any?>()
        val removedKeys = linkedSetOf<String>()
        var clearBeforeWrite = false
        lateinit var editor: SharedPreferences.Editor
        editor = proxy(SharedPreferences.Editor::class.java, InvocationHandler { _, method, args ->
            val arguments = args.orEmpty()
            when (method.name) {
                "putString", "putStringSet", "putInt", "putLong", "putFloat", "putBoolean" -> {
                    val key = arguments[0] as String
                    changedValues[key] = arguments[1]
                    removedKeys.remove(key)
                    editor
                }
                "remove" -> {
                    val key = arguments[0] as String
                    changedValues.remove(key)
                    removedKeys.add(key)
                    editor
                }
                "clear" -> {
                    clearBeforeWrite = true
                    editor
                }
                "commit" -> {
                    applyChanges(changedValues, removedKeys, clearBeforeWrite)
                    true
                }
                "apply" -> {
                    applyChanges(changedValues, removedKeys, clearBeforeWrite)
                    null
                }
                "toString" -> "InMemorySharedPreferences.Editor"
                "hashCode" -> System.identityHashCode(editor)
                "equals" -> editor === arguments.firstOrNull()
                else -> error("Unexpected SharedPreferences.Editor call: ${method.name}")
            }
        })
        return editor
    }

    private fun applyChanges(changedValues: Map<String, Any?>, removedKeys: Set<String>, clearBeforeWrite: Boolean) {
        if (clearBeforeWrite) values.clear()
        removedKeys.forEach(values::remove)
        values.putAll(changedValues)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> proxy(type: Class<T>, handler: InvocationHandler): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type), handler) as T
}
