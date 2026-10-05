package com.wanderwildwood.kotozute.feature.signal

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.LruCache
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContract
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.wanderwildwood.kotozute.R
import com.wanderwildwood.kotozute.common.base.QkThemedActivity
import com.wanderwildwood.kotozute.common.util.extensions.setVisible
import com.wanderwildwood.kotozute.databinding.SignalGifActivityBinding
import com.wanderwildwood.kotozute.signalstore.Giphy
import dagger.android.AndroidInjection
import timber.log.Timber
import kotlin.concurrent.thread

/**
 * Signal's GIF keyboard, as a screen: trending when it opens, a search on the keyboard's
 * Search key, and a tap picks one (forum #108). See [Giphy] for where the requests go.
 *
 * Stills, not moving previews. Upstream plays each tile's MP4 preview; on this panel a grid of
 * twenty animations is a screen that never stops repainting, and the still says which GIF it is.
 *
 * Hands back only the chosen GIF's MP4 address. The composer fetches it: the bytes are up to
 * two megabytes, and an Intent result that size does not survive the trip back.
 */
class SignalGifActivity : QkThemedActivity() {

    private lateinit var binding: SignalGifActivityBinding
    private val adapter = GifAdapter()

    private val gifs = mutableListOf<Giphy.Gif>()
    private var query = ""
    private var hasMore = false
    private var loading = false

    /** Bumped by every new search, so a page that arrives for an older one is dropped. */
    private var generation = 0

    private val stills = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val inFlight = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        AndroidInjection.inject(this)
        super.onCreate(savedInstanceState)
        binding = SignalGifActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.signal_gif_title)

        val grid = GridLayoutManager(this, COLUMNS)
        binding.recyclerView.layoutManager = grid
        binding.recyclerView.adapter = adapter
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (hasMore && !loading && grid.findLastVisibleItemPosition() >= gifs.size - COLUMNS * 2) load()
            }
        })

        // The keyboard's Search key, or Enter: a hardware key arrives as a key event with no
        // action, and is answered only on the way down so one press is one search.
        binding.search.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == android.view.KeyEvent.KEYCODE_ENTER
            if (enter && event?.action != android.view.KeyEvent.ACTION_DOWN) return@setOnEditorActionListener true
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                query = binding.search.text?.toString().orEmpty()
                // Down, so the results have the screen rather than a third of it.
                getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    ?.hideSoftInputFromWindow(binding.search.windowToken, 0)
                gifs.clear()
                adapter.notifyDataSetChanged()
                generation++
                loading = false
                load()
                true
            } else false
        }

        load()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /** The next page of [query] -- trending when it is blank. */
    private fun load() {
        loading = true
        val asked = generation
        val offset = gifs.size
        if (gifs.isEmpty()) showEmpty(R.string.signal_gif_loading)
        thread(isDaemon = true) {
            val page = runCatching { Giphy.search(query, offset, PAGE) }
                .onFailure { Timber.w(it, "signal gif: search failed") }
            runOnUiThread {
                if (asked != generation || isFinishing) return@runOnUiThread
                loading = false
                page.onSuccess {
                    val start = gifs.size
                    gifs += it.gifs
                    hasMore = it.hasMore && it.gifs.isNotEmpty()
                    adapter.notifyItemRangeInserted(start, it.gifs.size)
                    if (gifs.isEmpty()) showEmpty(R.string.signal_gif_none) else showGrid()
                }.onFailure {
                    if (gifs.isEmpty()) showEmpty(R.string.signal_gif_search_failed)
                }
            }
        }
    }

    private fun showEmpty(text: Int) {
        binding.empty.setText(text)
        binding.empty.setVisible(true)
        binding.recyclerView.setVisible(false)
    }

    private fun showGrid() {
        binding.empty.setVisible(false)
        binding.recyclerView.setVisible(true)
    }

    private fun pick(gif: Giphy.Gif) {
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_MP4, gif.mp4Url))
        finish()
    }

    private inner class GifAdapter : RecyclerView.Adapter<GifHolder>() {
        override fun getItemCount() = gifs.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = GifHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.signal_gif_item, parent, false) as ImageView
        )

        override fun onBindViewHolder(holder: GifHolder, position: Int) {
            val gif = gifs[position]
            val image = holder.image
            image.tag = gif.stillUrl
            image.setOnClickListener { pick(gif) }
            val cached = stills.get(gif.stillUrl)
            image.setImageBitmap(cached)
            if (cached == null) fetchStill(gif.stillUrl)
        }
    }

    /** One still into the cache, then whichever tile holds it now is redrawn. */
    private fun fetchStill(url: String) {
        if (!inFlight.add(url)) return
        thread(isDaemon = true) {
            val bmp = runCatching { Giphy.fetch(url) }.getOrNull()
                ?.let { SignalAttachment.decodeBounded(it) }
            runOnUiThread {
                inFlight.remove(url)
                if (bmp == null || isFinishing) return@runOnUiThread
                stills.put(url, bmp)
                val index = gifs.indexOfFirst { it.stillUrl == url }
                if (index >= 0) adapter.notifyItemChanged(index)
            }
        }
    }

    private class GifHolder(val image: ImageView) : RecyclerView.ViewHolder(image)

    /** Launches the picker; the result is the chosen GIF's MP4 address, or null. */
    class Pick : ActivityResultContract<Unit, String?>() {
        override fun createIntent(context: Context, input: Unit) = Intent(context, SignalGifActivity::class.java)
        override fun parseResult(resultCode: Int, intent: Intent?): String? =
            intent?.getStringExtra(EXTRA_MP4)?.takeIf { resultCode == Activity.RESULT_OK }
    }

    companion object {
        private const val EXTRA_MP4 = "mp4"
        private const val COLUMNS = 2
        /** Upstream's keyboard pages by 20 at a time. */
        private const val PAGE = 20
    }
}
