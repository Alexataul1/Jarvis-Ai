package com.example.service

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-Device Utility Command Dispatcher (App Launch, Contacts/Calling, Battery, Date/Time, Calculator).
 * Strictly handles explicit device utility actions—never impersonates an LLM for general chat or Q&A.
 */
class OfflineAssistantEngine(private val deviceManager: DeviceAssistantManager) {

    /**
     * Returns an [OfflineResponse] if [query] matches an explicit device control or math utility,
     * or `null` if it should be answered by an actual LLM (Gemini / local Llama GGUF).
     */
    fun processDeviceActionOrNull(query: String): OfflineResponse? {
        val q = query.trim().lowercase(Locale.getDefault())
        if (q.isEmpty()) return null

        // 1. App Launching Intent
        if (q.startsWith("open ") || q.startsWith("launch ") || q.contains("kholo") || q.contains("open karo") || q.contains("chalao")) {
            val appQuery = q
                .replace("open ", "")
                .replace("launch ", "")
                .replace("kholo", "")
                .replace("open karo", "")
                .replace("chalao", "")
                .replace("app", "")
                .trim()

            if (appQuery.isNotEmpty()) {
                val launchResult = deviceManager.launchAppByName(appQuery)
                return OfflineResponse(
                    text = launchResult,
                    actionType = ActionType.APP_LAUNCH
                )
            }
        }

        // 2. Contacts & Calling Intent
        if (q.startsWith("call ") || q.contains("call lagao") || q.contains("phone lagao") || q.contains("call karo")) {
            val contactName = q
                .replace("call ", "")
                .replace("call lagao", "")
                .replace("phone lagao", "")
                .replace("call karo", "")
                .replace("ko", "")
                .trim()

            val contacts = deviceManager.getContacts()
            if (contacts.isEmpty()) {
                return OfflineResponse(
                    text = "Contacts permission not granted or no contacts found. Allow Contacts permission from the menu to call directly.",
                    actionType = ActionType.CONTACTS
                )
            }

            val matched = contacts.firstOrNull { it.name.lowercase(Locale.getDefault()).contains(contactName) }
            return if (matched != null) {
                deviceManager.dialPhoneNumber(matched.phoneNumber)
                OfflineResponse(
                    text = "Dialing ${matched.name} (${matched.phoneNumber})...",
                    actionType = ActionType.CONTACTS
                )
            } else {
                OfflineResponse(
                    text = "No contact matching '$contactName' was found.",
                    actionType = ActionType.CONTACTS
                )
            }
        }

        // 3. Show Contacts list
        if (q == "contacts" || q == "show contacts" || q == "contact list" || q.contains("number dikhao") || q.contains("phone book")) {
            val contacts = deviceManager.getContacts()
            if (contacts.isEmpty()) {
                return OfflineResponse(
                    text = "Contacts list is empty or Contacts permission is needed.",
                    actionType = ActionType.CONTACTS
                )
            }
            val preview = contacts.take(5).joinToString("\n") { "• ${it.name}: ${it.phoneNumber}" }
            val extra = if (contacts.size > 5) "\n...and ${contacts.size - 5} more contacts." else ""
            return OfflineResponse(
                text = "Your Contacts (${contacts.size}):\n$preview$extra",
                actionType = ActionType.CONTACTS
            )
        }

        // 4. Battery Level
        if (q == "battery" || q == "battery level" || q == "check battery" || q.contains("battery kitni") || q.contains("charging status")) {
            val level = deviceManager.getBatteryLevel()
            val text = if (level >= 0) "Battery Level: $level%" else "Battery information unavailable."
            return OfflineResponse(text = text, actionType = ActionType.DEVICE_INFO)
        }

        // 5. Date & Time
        if (q == "time" || q == "date" || q == "what time is it" || q == "what is the time" || q == "aaj ka din" || q == "kitne baje hain" || q == "samay kya hai") {
            val timeFmt = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
            val dateFmt = SimpleDateFormat("EEEE, dd MMMM yyyy", Locale.getDefault()).format(Date())
            return OfflineResponse(
                text = "Time: $timeFmt\nDate: $dateFmt",
                actionType = ActionType.DEVICE_INFO
            )
        }

        // 6. Math evaluation
        val mathResult = tryEvaluateMath(q)
        if (mathResult != null) {
            return OfflineResponse(
                text = mathResult,
                actionType = ActionType.CALCULATOR
            )
        }

        return null
    }

    /**
     * Legacy entrypoint preserved for compatibility; returns only real device actions or explicit no-model notice.
     */
    fun processQuery(query: String): OfflineResponse {
        processDeviceActionOrNull(query)?.let { return it }
        return OfflineResponse(
            text = "No local Llama model is loaded to answer conversational queries offline. Please download Meta Llama 3.2 1B Instruct in Offline AI Brain & Models.",
            actionType = ActionType.GENERAL
        )
    }

    private fun tryEvaluateMath(query: String): String? {
        val clean = query.replace("calculate", "")
            .replace("what is", "")
            .replace("solve", "")
            .replace("x", "*")
            .replace("plus", "+")
            .replace("minus", "-")
            .replace("multiply by", "*")
            .replace("divided by", "/")
            .trim()

        val pattern = Regex("""^([\d.]+)\s*([\+\-\*\/])\s*([\d.]+)$""")
        val match = pattern.find(clean) ?: return null

        val (num1Str, op, num2Str) = match.destructured
        val n1 = num1Str.toDoubleOrNull() ?: return null
        val n2 = num2Str.toDoubleOrNull() ?: return null

        val res = when (op) {
            "+" -> n1 + n2
            "-" -> n1 - n2
            "*" -> n1 * n2
            "/" -> if (n2 != 0.0) n1 / n2 else Double.NaN
            else -> return null
        }

        val display = if (res % 1.0 == 0.0) res.toLong().toString() else "%.4f".format(res)
        return "$n1 $op $n2 = $display"
    }
}

data class OfflineResponse(
    val text: String,
    val actionType: ActionType
)

enum class ActionType {
    APP_LAUNCH,
    CONTACTS,
    DEVICE_INFO,
    CALCULATOR,
    GENERAL
}
