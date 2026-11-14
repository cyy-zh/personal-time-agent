package io.github.cyyzh.personaltimeagent.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ProviderConfig(val provider:String,val endpoint:String,val model:String,val temperature:Float,
    val allowTasks:Boolean,val allowGoals:Boolean,val allowLedger:Boolean,val allowNotifications:Boolean)

class ProviderStore(context:Context) {
    private val prefs=context.applicationContext.getSharedPreferences("provider",Context.MODE_PRIVATE)
    fun config()=ProviderConfig(prefs.getString("provider","")?:"",prefs.getString("endpoint","")?:"",
        prefs.getString("model","")?:"",prefs.getFloat("temperature",0.3f),prefs.getBoolean("tasks",false),
        prefs.getBoolean("goals",false),prefs.getBoolean("ledger",false),prefs.getBoolean("notifications",false))
    fun save(c:ProviderConfig)=prefs.edit().putString("provider",c.provider).putString("endpoint",c.endpoint)
        .putString("model",c.model).putFloat("temperature",c.temperature).putBoolean("tasks",c.allowTasks)
        .putBoolean("goals",c.allowGoals).putBoolean("ledger",c.allowLedger).putBoolean("notifications",c.allowNotifications).commit()
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (store.getKey("pta-api-key",null) as? SecretKey)?.let{return it}
        val gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder("pta-api-key",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return gen.generateKey()
    }
    fun saveApiKey(raw:String) { val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key())
        val enc=cipher.doFinal(raw.toByteArray(Charsets.UTF_8));prefs.edit().putString("key_iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP))
            .putString("key_data",Base64.encodeToString(enc,Base64.NO_WRAP)).commit() }
    fun apiKey():String? { val iv=prefs.getString("key_iv",null)?:return null;val data=prefs.getString("key_data",null)?:return null
        return runCatching { val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP)))
            cipher.doFinal(Base64.decode(data,Base64.NO_WRAP)).toString(Charsets.UTF_8) }.getOrNull() }
    fun clearApiKey()=prefs.edit().remove("key_iv").remove("key_data").commit()
}
