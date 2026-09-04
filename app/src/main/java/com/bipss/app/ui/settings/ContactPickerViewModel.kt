package com.bipss.app.ui.settings

import android.content.Context
import android.provider.ContactsContract
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bipss.app.data.db.dao.EmergencyContactDao
import com.bipss.app.data.db.entity.EmergencyContactEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val MAX_EMERGENCY_CONTACTS = 5

data class DeviceContact(
    val contactId: Long,
    val name: String,
    val phoneNumber: String
)

data class ContactPickerUiState(
    val existingCount: Int = 0,
    val deviceContacts: List<DeviceContact> = emptyList(),
    val selectedIds: Set<Long> = emptySet(),
    val isLoading: Boolean = false,
    val saved: Boolean = false
) {
    val totalSelectable: Int get() = MAX_EMERGENCY_CONTACTS - existingCount
    val remainingSlots: Int get() = (totalSelectable - selectedIds.size).coerceAtLeast(0)
}

/**
 * Backs the in-app emergency-contact picker (see ContactPickerScreen). Reads
 * from the device's own Contacts provider — Android has no native
 * multi-select contact-picker intent, so this queries
 * ContactsContract.CommonDataKinds.Phone directly and lets the user check up
 * to (5 - already-saved) contacts in one screen, matching the "select up to
 * 5 from one panel" requirement.
 */
class ContactPickerViewModel(
    private val emergencyContactDao: EmergencyContactDao,
    private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(ContactPickerUiState())
    val uiState: StateFlow<ContactPickerUiState> = _uiState.asStateFlow()

    fun loadDeviceContacts() {
        viewModelScope.launch {
            val existing = emergencyContactDao.all().first().size
            _uiState.update { it.copy(isLoading = true, existingCount = existing) }
            val contacts = withContext(Dispatchers.IO) { queryDeviceContacts() }
            _uiState.update { it.copy(deviceContacts = contacts, isLoading = false) }
        }
    }

    private fun queryDeviceContacts(): List<DeviceContact> {
        val result = mutableListOf<DeviceContact>()
        val seenContactIds = mutableSetOf<Long>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIdx)
                if (!seenContactIds.add(id)) continue // one row per contact — skip extra numbers
                val name = cursor.getString(nameIdx) ?: continue
                val number = cursor.getString(numberIdx) ?: continue
                result.add(DeviceContact(id, name, number))
            }
        }
        return result
    }

    fun toggleSelection(contactId: Long) {
        val state = _uiState.value
        val isSelected = contactId in state.selectedIds
        if (!isSelected && state.remainingSlots <= 0) return
        _uiState.update {
            it.copy(selectedIds = if (isSelected) it.selectedIds - contactId else it.selectedIds + contactId)
        }
    }

    fun save() {
        viewModelScope.launch {
            val state = _uiState.value
            val chosen = state.deviceContacts.filter { it.contactId in state.selectedIds }
            chosen.forEach { c ->
                emergencyContactDao.insert(EmergencyContactEntity(name = c.name, phoneNumber = c.phoneNumber))
            }
            _uiState.update { it.copy(saved = true) }
        }
    }
}
