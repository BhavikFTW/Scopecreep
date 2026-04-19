package com.scopecreep.service

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

object ApiKeyStore {

    private const val OPENAI_KEY = "openai.api_key"

    private fun attrs(keyName: String): CredentialAttributes =
        CredentialAttributes(generateServiceName("Scopecreep", keyName))

    fun getOpenAiKey(): String? =
        PasswordSafe.instance.getPassword(attrs(OPENAI_KEY))?.takeIf { it.isNotBlank() }

    fun setOpenAiKey(key: String?) {
        val creds = if (key.isNullOrBlank()) null else Credentials("openai", key)
        PasswordSafe.instance.set(attrs(OPENAI_KEY), creds)
    }
}
