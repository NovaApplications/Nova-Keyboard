package com.novaos.novakeyboard

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SpellCheckerSession
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import android.view.textservice.TextServicesManager
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.*
import androidx.savedstate.*
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.novaos.novakeyboard.ui.theme.NovaKeyboardTheme
import kotlinx.coroutines.delay

class NovaKeyboardService : InputMethodService(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner, SpellCheckerSession.SpellCheckerSessionListener {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val _viewModelStore = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = _viewModelStore

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private var isShifted by mutableStateOf(false)
    private var isSymbolMode by mutableStateOf(false)
    private var isEmojiMode by mutableStateOf(false)
    private var isListening by mutableStateOf(false)
    private var isClipboardMode by mutableStateOf(false)
    private val clipboardHistory = mutableStateListOf<String>()
    
    private var suggestions = mutableStateListOf<String>()
    private var spellCheckerSession: SpellCheckerSession? = null

    enum class KeyboardLayoutMode {
        STRETCH, CENTERED, SPLIT
    }
    private var layoutMode by mutableStateOf(KeyboardLayoutMode.CENTERED)

    private var speechRecognizer: SpeechRecognizer? = null
    
    private val backgroundColor = Color(0xFF202124) // Gboard dark background
    private val keyColor = Color(0xFF3C4043) // Gboard dark key
    private val onKeyColor = Color.White
    private val accentColor = Color(0xFF8AB4F8) // Gboard blue accent

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { isListening = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { isListening = false }
            override fun onError(error: Int) { isListening = false }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    currentInputConnection?.commitText(matches[0] + " ", 1)
                }
                isListening = false
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        
        val tsm = getSystemService(TEXT_SERVICES_MANAGER_SERVICE) as TextServicesManager
        spellCheckerSession = tsm.newSpellCheckerSession(null, null, this, true)
    }

    override fun onGetSuggestions(results: Array<out SuggestionsInfo>?) {
        val newSuggestions = mutableListOf<String>()
        results?.forEach { result ->
            for (i in 0 until result.suggestionsCount) {
                newSuggestions.add(result.getSuggestionAt(i))
            }
        }
        suggestions.clear()
        suggestions.addAll(newSuggestions.take(5))
    }

    override fun onGetSentenceSuggestions(results: Array<out SentenceSuggestionsInfo>?) {
        val newSuggestions = mutableListOf<String>()
        results?.forEach { result ->
            for (i in 0 until result.suggestionsCount) {
                val sinfo = result.getSuggestionsInfoAt(i)
                for (j in 0 until sinfo.suggestionsCount) {
                    newSuggestions.add(sinfo.getSuggestionAt(j))
                }
            }
        }
        suggestions.clear()
        suggestions.addAll(newSuggestions.distinct().take(5))
    }

    private fun updateSuggestions() {
        val ic = currentInputConnection ?: return
        val textBefore = ic.getTextBeforeCursor(20, 0)
        if (!textBefore.isNullOrBlank()) {
            val lastWord = textBefore.split(Regex("\\s+")).lastOrNull()
            if (!lastWord.isNullOrBlank()) {
                spellCheckerSession?.getSuggestions(TextInfo(lastWord), 5)
            } else {
                suggestions.clear()
            }
        } else {
            suggestions.clear()
        }
    }

    private fun startVoiceInput() {
        if (isListening) {
            speechRecognizer?.stopListening()
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        }
        speechRecognizer?.startListening(intent)
    }

    override fun onCreateInputView(): View {
        val composeView = ComposeView(this)
        
        window?.window?.decorView?.let { decorView ->
            decorView.setViewTreeLifecycleOwner(this)
            decorView.setViewTreeViewModelStoreOwner(this)
            decorView.setViewTreeSavedStateRegistryOwner(this)
        }

        composeView.apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                NovaKeyboardTheme {
                    KeyboardView()
                }
            }
        }
        return composeView
    }

    @Composable
    fun KeyboardView() {
        val configuration = LocalConfiguration.current
        val isTablet = configuration.smallestScreenWidthDp >= 600
        val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        
        val showLayoutToggle = isTablet || isLandscape

        val alphabetKeys = listOf(
            listOf("Q", "W", "E", "R", "T", "Y", "U", "I", "O", "P"),
            listOf("A", "S", "D", "F", "G", "H", "J", "K", "L"),
            listOf("⇧", "Z", "X", "C", "V", "B", "N", "M", "⌫"),
        )
        val symbolKeys = listOf(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
            listOf("@", "#", "$", "%", "&", "-", "+", "(", ")"),
            listOf("=", "*", "\"", "'", ":", ";", "!", "?", "⌫"),
        )

        val currentKeys = if (isSymbolMode) symbolKeys else alphabetKeys

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(backgroundColor),
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                modifier = Modifier
                    .then(
                        if (layoutMode == KeyboardLayoutMode.CENTERED && isTablet) Modifier.widthIn(max = 720.dp)
                        else Modifier.fillMaxWidth()
                    )
                    .padding(horizontal = 2.dp)
                    .padding(top = 8.dp, bottom = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Toolbar and Suggestions
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ToolbarIconButton("⚙") {
                            val intent = Intent(this@NovaKeyboardService, MainActivity::class.java)
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(intent)
                        }
                        ToolbarIconButton("📋") {
                            provideFeedback()
                            refreshClipboardHistory()
                            isClipboardMode = !isClipboardMode
                        }
                        ToolbarIconButton(if (isListening) "🛑" else "🎤") {
                            provideFeedback()
                            startVoiceInput()
                        }
                        
                        VerticalDivider(modifier = Modifier.height(20.dp).padding(horizontal = 4.dp), color = onKeyColor.copy(alpha = 0.2f))
                        
                        LazyRow(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            lazyItems(suggestions) { suggestion ->
                                Text(
                                    text = suggestion,
                                    color = onKeyColor,
                                    modifier = Modifier
                                        .padding(horizontal = 12.dp)
                                        .clickable {
                                            handleSuggestionClick(suggestion)
                                        },
                                    fontSize = 16.sp
                                )
                            }
                        }

                        if (showLayoutToggle) {
                            ToolbarIconButton(
                                when(layoutMode) {
                                    KeyboardLayoutMode.STRETCH -> "⬌"
                                    KeyboardLayoutMode.CENTERED -> "▭"
                                    KeyboardLayoutMode.SPLIT -> "✂"
                                }
                            ) {
                                provideFeedback()
                                layoutMode = KeyboardLayoutMode.entries[(layoutMode.ordinal + 1) % KeyboardLayoutMode.entries.size]
                            }
                        }
                    }
                }

                if (isClipboardMode) {
                    ClipboardView()
                } else if (isEmojiMode) {
                    EmojiView()
                } else {
                    currentKeys.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            if (layoutMode == KeyboardLayoutMode.SPLIT && (isTablet || isLandscape)) {
                                val mid = (row.size + 1) / 2
                                val left = row.take(mid)
                                val right = row.drop(mid)
                                
                                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    left.forEach { key -> KeyButtonWrapper(key) }
                                }
                                Spacer(modifier = Modifier.weight(0.5f))
                                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    right.forEach { key -> KeyButtonWrapper(key) }
                                }
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    row.forEach { key -> KeyButtonWrapper(key) }
                                }
                            }
                        }
                    }

                    // Bottom Row
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (layoutMode == KeyboardLayoutMode.SPLIT && (isTablet || isLandscape)) {
                            // Split Bottom Row
                            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                KeyButton(text = if (isSymbolMode) "ABC" else "?123", modifier = Modifier.weight(1.5f), containerColor = keyColor) {
                                    provideFeedback(); isSymbolMode = !isSymbolMode
                                }
                                KeyButton(text = "/", modifier = Modifier.weight(1f)) { provideFeedback(); handleKeyPress("/") }
                                KeyButton(text = "Space", modifier = Modifier.weight(2f)) { provideFeedback(); handleKeyPress(" ") }
                            }
                            Spacer(modifier = Modifier.weight(0.2f))
                            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                KeyButton(text = "Space", modifier = Modifier.weight(2f)) { provideFeedback(); handleKeyPress(" ") }
                                KeyButton(text = ".", modifier = Modifier.weight(1f)) { provideFeedback(); handleKeyPress(".") }
                                KeyButton(text = "➔", modifier = Modifier.weight(1.5f), containerColor = accentColor) { 
                                    provideFeedback(); handleKeyPress("\n") 
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                KeyButton(text = if (isSymbolMode) "ABC" else "?123", modifier = Modifier.weight(1.5f), containerColor = keyColor) {
                                    provideFeedback(); isSymbolMode = !isSymbolMode
                                }
                                KeyButton(text = "/", modifier = Modifier.weight(1f)) { provideFeedback(); handleKeyPress("/") }
                                KeyButton(text = "😀", modifier = Modifier.weight(1f)) { 
                                    provideFeedback()
                                    isEmojiMode = !isEmojiMode
                                }
                                KeyButton(text = "Space", modifier = Modifier.weight(4f)) { provideFeedback(); handleKeyPress(" ") }
                                KeyButton(text = ".", modifier = Modifier.weight(1f)) { provideFeedback(); handleKeyPress(".") }
                                KeyButton(
                                    text = "➔", 
                                    modifier = Modifier.weight(1.5f),
                                    containerColor = accentColor 
                                ) { 
                                    provideFeedback(); handleKeyPress("\n") 
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun EmojiView() {
        val emojis = listOf(
            "😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "😇",
            "🙂", "🙃", "😉", "😌", "😍", "🥰", "😘", "😗", "😙", "😚",
            "😋", "😛", "😝", "😜", "🤪", "🤨", "🧐", "🤓", "😎", "🤩",
            "🥳", "😏", "😒", "😞", "😔", "😟", "😕", "🙁", "☹️", "😣",
            "😖", "😫", "😩", "🥺", "😢", "😭", "😤", "😠", "😡", "🤬",
            "🤯", "😳", "🥵", "🥶", "😱", "😨", "😰", "😥", "😓", "🤗",
            "🤔", "🤭", "🤫", "🤥", "😶", "😐", "😑", "😬", "🙄", "😯",
            "😦", "😧", "😮", "😲", "🥱", "😴", "🤤", "😪", "😵", "🤐",
            "🥴", "🤢", "🤮", "🤧", "😷", "🤒", "🤕", "🤑", "🤠", "😈"
        )
        
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp) // Increased height to accommodate bottom bar
        ) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item { Text("Recent", color = accentColor, fontSize = 12.sp) }
                item { Text("Smileys", color = onKeyColor.copy(alpha = 0.6f), fontSize = 12.sp) }
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(8),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(8.dp)
            ) {
                gridItems(emojis) { emoji ->
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clickable {
                                provideFeedback()
                                currentInputConnection?.commitText(emoji, 1)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = emoji, fontSize = 24.sp)
                    }
                }
            }

            // Emoji Navigation / Bottom Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                KeyButton(
                    text = "ABC",
                    modifier = Modifier.weight(1.5f),
                    containerColor = keyColor
                ) {
                    provideFeedback()
                    isEmojiMode = false
                }
                
                Spacer(modifier = Modifier.weight(4f))
                
                KeyButton(
                    text = "⌫",
                    modifier = Modifier.weight(1.5f),
                    containerColor = keyColor
                ) {
                    provideFeedback()
                    handleKeyPress("⌫")
                }
            }
        }
    }

    @Composable
    private fun ClipboardView() {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Recent Clips",
                color = onKeyColor.copy(alpha = 0.7f),
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            
            if (clipboardHistory.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No clips found", color = onKeyColor.copy(alpha = 0.5f))
                }
            } else {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    lazyItems(clipboardHistory) { clip ->
                        Surface(
                            modifier = Modifier
                                .widthIn(max = 200.dp)
                                .fillMaxHeight(0.8f)
                                .clickable {
                                    provideFeedback()
                                    currentInputConnection?.commitText(clip, 1)
                                    isClipboardMode = false
                                },
                            color = keyColor,
                            shape = RectangleShape
                        ) {
                            Box(modifier = Modifier.padding(8.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    text = clip,
                                    color = onKeyColor,
                                    fontSize = 14.sp,
                                    maxLines = 4
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ToolbarIconButton(icon: String, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Text(text = icon, color = onKeyColor, fontSize = 24.sp)
        }
    }

    @Composable
    private fun RowScope.KeyButtonWrapper(key: String) {
        val isSpecial = key == "⌫" || key == "⇧"
        val displayKey = if (isSpecial || key.length > 1) {
            key
        } else {
            if (!isSymbolMode && isShifted) key.uppercase() else key.lowercase()
        }
        
        KeyButton(
            text = displayKey,
            modifier = Modifier.weight(if (isSpecial) 1.5f else 1f),
            containerColor = if (key == "⇧" && isShifted) Color(0xFF4A5060) else keyColor,
            repeatOnClick = key == "⌫"
        ) {
            provideFeedback()
            handleKeyPress(key)
        }
    }

    @Composable
    fun KeyButton(
        text: String,
        modifier: Modifier = Modifier,
        containerColor: Color = keyColor,
        repeatOnClick: Boolean = false,
        onClick: () -> Unit
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        val isPressed by interactionSource.collectIsPressedAsState()

        if (repeatOnClick && isPressed) {
            LaunchedEffect(Unit) {
                delay(400) // Initial delay before repeating
                while (true) {
                    onClick()
                    delay(50) // Speed of repeating deletions
                }
            }
        }

        Surface(
            modifier = modifier
                .padding(horizontal = 2.dp, vertical = 2.dp)
                .height(58.dp)
                .shadow(elevation = 1.dp, shape = RoundedCornerShape(6.dp))
                .clickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    onClick = onClick
                ),
            shape = RoundedCornerShape(6.dp),
            color = containerColor,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = text,
                    fontSize = 18.sp,
                    color = onKeyColor
                )
            }
        }
    }

    private fun provideFeedback() {
        window?.window?.decorView?.performHapticFeedback(
            HapticFeedbackConstants.KEYBOARD_TAP,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        am?.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD)
    }

    private fun handleSuggestionClick(suggestion: String) {
        provideFeedback()
        val ic = currentInputConnection ?: return
        val textBefore = ic.getTextBeforeCursor(20, 0)
        if (!textBefore.isNullOrBlank()) {
            val words = textBefore.split(Regex("\\s+"))
            val lastWordLength = words.last().length
            ic.deleteSurroundingText(lastWordLength, 0)
            ic.commitText(suggestion + " ", 1)
        }
        suggestions.clear()
    }

    private fun handleKeyPress(key: String) {
        val ic = currentInputConnection ?: return
        when (key) {
            "⌫" -> {
                ic.deleteSurroundingText(1, 0)
                updateSuggestions()
            }
            "⇧" -> isShifted = !isShifted
            "\n" -> {
                ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER))
                suggestions.clear()
            }
            " " -> {
                ic.commitText(" ", 1)
                suggestions.clear()
            }
            else -> {
                val textToCommit = if (!isSymbolMode && isShifted) key.uppercase() else if (!isSymbolMode) key.lowercase() else key
                ic.commitText(textToCommit, 1)
                if (!isSymbolMode && isShifted) isShifted = false
                updateSuggestions()
            }
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        refreshClipboardHistory()
    }

    private fun refreshClipboardHistory() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = clipboard?.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString()
            if (!text.isNullOrBlank() && !clipboardHistory.contains(text)) {
                clipboardHistory.add(0, text)
                if (clipboardHistory.size > 10) clipboardHistory.removeAt(10)
            }
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        _viewModelStore.clear()
        speechRecognizer?.destroy()
        speechRecognizer = null
        spellCheckerSession?.close()
        spellCheckerSession = null
    }
}
