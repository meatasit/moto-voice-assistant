package com.moto.voice

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.moto.voice.data.FavoritesStore
import com.moto.voice.databinding.ActivityFavoritesBinding

class FavoritesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFavoritesBinding
    private lateinit var store: FavoritesStore

    private val pickContact = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: run {
            toast("ไม่พบข้อมูลที่เลือก ลองใหม่นะคะ")
            return@registerForActivityResult
        }
        val picked = readContact(uri)
        if (picked == null) {
            // Silent-failure fix (v1.3.4 field-test): before we'd return null and render
            // nothing — the user thought they added a favorite when in fact permission or
            // the cursor had failed. Speak up so the rider knows to check permissions.
            toast("อ่านข้อมูลไม่ได้ ตรวจสิทธิ์รายชื่อในหน้าตั้งค่าก่อนนะคะ")
            return@registerForActivityResult
        }
        val added = store.add(FavoritesStore.Favorite(picked.id, picked.name, picked.phone))
        if (!added) {
            binding.tvFullWarning.text = "เพิ่มไม่ได้ — ซ้ำหรือครบแล้ว"
        } else if (picked.phone.isNullOrBlank()) {
            // Not an error — the favorite is stored — but tell the user the fallback
            // won't work if their contact's ID ever drifts. Helps them fix the source.
            toast("เพิ่ม ${picked.name} แล้ว แต่ไม่พบเบอร์ในรายชื่อ — โปรดเพิ่มเบอร์")
        }
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFavoritesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.apply { title = "รายการโปรด"; setDisplayHomeAsUpEnabled(true) }

        store = FavoritesStore(this)
        binding.btnAddFavorite.setOnClickListener { launchContactPicker() }
        render()
    }

    override fun onResume() { super.onResume(); render() }

    private fun render() {
        binding.favoritesContainer.removeAllViews()
        val items = store.list()
        if (items.isEmpty()) {
            binding.favoritesContainer.addView(TextView(this).apply {
                text = "ยังไม่มีรายการโปรด"
                setTextColor(android.graphics.Color.parseColor("#AAFFFFFF"))
                textSize = 14f
                setPadding(8, 8, 8, 8)
            })
        }
        items.forEachIndexed { index, fav ->
            val row = layoutInflater.inflate(R.layout.item_favorite, binding.favoritesContainer, false)
            row.findViewById<TextView>(R.id.tvFavSlot).text = (index + 1).toString()
            row.findViewById<TextView>(R.id.tvFavName).text = fav.displayName
            row.findViewById<MaterialButton>(R.id.btnRemoveFav).setOnClickListener {
                store.remove(fav.contactId); render()
            }
            binding.favoritesContainer.addView(row)
        }
        binding.btnAddFavorite.isEnabled = !store.isFull()
        binding.tvFullWarning.visibility = if (store.isFull()) View.VISIBLE else View.GONE
        if (store.isFull()) binding.tvFullWarning.text = "ครบ ${FavoritesStore.MAX} คนแล้ว ลบก่อนเพิ่ม"
    }

    private fun launchContactPicker() {
        val intent = Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI)
        pickContact.launch(intent)
    }

    private data class PickedContact(val id: String, val name: String, val phone: String?)

    /**
     * Two queries because the picker gives us the aggregate contact URI (Contacts._ID
     * + DISPLAY_NAME), but the phone number lives in
     * [ContactsContract.CommonDataKinds.Phone] keyed by CONTACT_ID. We take the first
     * phone entry — if the contact has multiple, prompting on this screen would add
     * friction the rider doesn't need; the fallback path in the pipeline dials this
     * number when contact-ID resolution fails post-sync.
     */
    private fun readContact(uri: Uri): PickedContact? {
        val cursor: Cursor = contentResolver.query(
            uri,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME),
            null, null, null,
        ) ?: return null
        val (id, name) = cursor.use { c ->
            if (!c.moveToFirst()) return null
            val id = c.getString(0) ?: return null
            val name = c.getString(1) ?: return null
            id to name
        }
        val phone = lookupPrimaryPhone(id)
        return PickedContact(id, name, phone)
    }

    private fun lookupPrimaryPhone(contactId: String): String? {
        val phoneCursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contactId),
            null,
        ) ?: return null
        return phoneCursor.use { c ->
            if (!c.moveToFirst()) null
            else c.getString(0)?.filter { it.isDigit() || it == '+' }?.takeIf {
                it.count { ch -> ch.isDigit() } >= 3
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
