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
        val uri = result.data?.data ?: return@registerForActivityResult
        val (id, name) = readContact(uri) ?: return@registerForActivityResult
        val added = store.add(FavoritesStore.Favorite(id, name))
        if (!added) binding.tvFullWarning.text = "เพิ่มไม่ได้ — ซ้ำหรือครบแล้ว"
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

    private fun readContact(uri: Uri): Pair<String, String>? {
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME,
        )
        val cursor: Cursor = contentResolver.query(uri, projection, null, null, null) ?: return null
        return cursor.use { c ->
            if (!c.moveToFirst()) return null
            val id = c.getString(0) ?: return null
            val name = c.getString(1) ?: return null
            id to name
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
