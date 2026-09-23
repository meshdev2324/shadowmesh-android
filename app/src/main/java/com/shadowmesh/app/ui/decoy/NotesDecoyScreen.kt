package com.shadowmesh.app.ui.decoy

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.util.*

data class NoteItem(
    val id: String,
    val title: String,
    val preview: String,
    val date: String,
    val color: Color = Color.White,
    val isSecret: Boolean = false,
    val isArchived: Boolean = false,
)

private val NoteColors =
    listOf(
        Color(0xFFFFFFFF), // White
        Color(0xFFF28B82), // Red
        Color(0xFFFBBC04), // Orange
        Color(0xFFFFF475), // Yellow
        Color(0xFFCCFF90), // Green
        Color(0xFFA7FFEB), // Teal
        Color(0xFFCBF0F8), // Blue
        Color(0xFFAECBFA), // Dark Blue
        Color(0xFFD7AEFB), // Purple
        Color(0xFFFDCFE8), // Pink
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesDecoyScreen(
    onExitDecoy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedNote by remember { mutableStateOf<NoteItem?>(null) }
    var isCreatingNote by remember { mutableStateOf(false) }
    var showArchived by remember { mutableStateOf(false) }

    val allNotes =
        remember {
            mutableStateListOf(
                NoteItem(
                    UUID.randomUUID().toString(),
                    "Grocery List",
                    "Milk\nEggs\nWhole wheat bread\nCoffee beans\nUnsalted butter",
                    "2 hours ago",
                    NoteColors[4],
                ),
                NoteItem(
                    UUID.randomUUID().toString(),
                    "App Ideas",
                    "1. Habit tracker with social features\n2. Recipe manager with AI\n3. Budgeting tool for freelancers",
                    "Yesterday",
                    NoteColors[6],
                ),
                NoteItem(
                    UUID.randomUUID().toString(),
                    "Personal Diary",
                    "Thoughts on the new project. It's moving fast but the architecture feels solid. Travel plans for the weekend include a quick trip to the coast.",
                    "Oct 21",
                    NoteColors[3],
                    isSecret = true,
                ),
                NoteItem(
                    UUID.randomUUID().toString(),
                    "Weekend Trip",
                    "Pack hiking boots\nCheck weather for Big Sur\nBook dinner at Nepenthe",
                    "3 days ago",
                    NoteColors[8],
                ),
                NoteItem(
                    UUID.randomUUID().toString(),
                    "Daily Journal",
                    "Started a new meditation routine today. Feeling much more focused and calm. The morning light was beautiful.",
                    "Oct 15",
                    NoteColors[0],
                ),
            )
        }

    val filteredNotes =
        allNotes.filter {
            it.isArchived == showArchived &&
                (it.title.contains(searchQuery, ignoreCase = true) || it.preview.contains(searchQuery, ignoreCase = true))
        }

    if (selectedNote != null || isCreatingNote) {
        NoteEditor(
            note = selectedNote,
            onBack = {
                selectedNote = null
                isCreatingNote = false
            },
            onSave = { title, content, color ->
                val currentNote = selectedNote
                if (currentNote != null) {
                    val index = allNotes.indexOfFirst { it.id == currentNote.id }
                    if (index != -1) {
                        allNotes[index] = currentNote.copy(title = title, preview = content, color = color)
                    }
                } else {
                    allNotes.add(
                        0,
                        NoteItem(
                            id = UUID.randomUUID().toString(),
                            title = title,
                            preview = content,
                            date = "Just now",
                            color = color,
                        ),
                    )
                }
                selectedNote = null
                isCreatingNote = false
            },
            onDelete = {
                val currentNote = selectedNote
                if (currentNote != null) {
                    allNotes.removeAll { it.id == currentNote.id }
                }
                selectedNote = null
                isCreatingNote = false
            },
            onArchive = {
                val currentNote = selectedNote
                if (currentNote != null) {
                    val index = allNotes.indexOfFirst { it.id == currentNote.id }
                    if (index != -1) {
                        allNotes[index] = allNotes[index].copy(isArchived = !allNotes[index].isArchived)
                    }
                }
                selectedNote = null
                isCreatingNote = false
            },
        )
    } else {
        Scaffold(
            topBar = {
                SearchAppBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    isArchivedMode = showArchived,
                    onToggleArchive = { showArchived = !showArchived },
                    onAvatarLongClick = onExitDecoy,
                )
            },
            floatingActionButton = {
                LargeFloatingActionButton(
                    onClick = { isCreatingNote = true },
                    containerColor = Color.White,
                    contentColor = Color(0xFF1A73E8),
                    shape = RoundedCornerShape(16.dp),
                    elevation = FloatingActionButtonDefaults.elevation(4.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = "New Note", modifier = Modifier.size(32.dp))
                }
            },
            containerColor = Color(0xFFF1F3F4),
            modifier = modifier.fillMaxSize(),
        ) { paddingValues ->
            if (filteredNotes.isEmpty()) {
                Box(modifier = Modifier.padding(paddingValues).fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            if (showArchived) Icons.Default.Archive else Icons.Default.Lightbulb,
                            contentDescription = null,
                            modifier = Modifier.size(120.dp),
                            tint = Color.LightGray.copy(alpha = 0.5f),
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            if (showArchived) "No archived notes" else "Notes you add appear here",
                            color = Color.Gray,
                        )
                    }
                }
            } else {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(2),
                    modifier =
                        Modifier
                            .padding(paddingValues)
                            .fillMaxSize()
                            .padding(horizontal = 8.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalItemSpacing = 8.dp,
                ) {
                    items(filteredNotes, key = { it.id }) { note ->
                        NoteCard(
                            note = note,
                            onSecretTrigger = onExitDecoy,
                            onClick = { selectedNote = note },
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SearchAppBar(
    query: String,
    onQueryChange: (String) -> Unit,
    isArchivedMode: Boolean,
    onToggleArchive: () -> Unit,
    onAvatarLongClick: () -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = 8.dp, start = 16.dp, end = 16.dp, bottom = 8.dp),
        shape = RoundedCornerShape(28.dp),
        color = Color.White,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier =
                Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onToggleArchive) {
                Icon(
                    if (isArchivedMode) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Menu,
                    contentDescription = "Menu",
                    tint = Color(0xFF5F6368),
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(if (isArchivedMode) "Search archive" else "Search your notes", color = Color(0xFF5F6368))
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(fontSize = 16.sp, color = Color.Black),
                    singleLine = true,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(Icons.Default.GridView, contentDescription = "Layout", tint = Color(0xFF5F6368))
            Spacer(modifier = Modifier.width(16.dp))
            Box(
                modifier =
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1A73E8))
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onLongPress = { onAvatarLongClick() },
                            )
                        },
                contentAlignment = Alignment.Center,
            ) {
                Text("S", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun NoteCard(
    note: NoteItem,
    onSecretTrigger: () -> Unit,
    onClick: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { if (note.isSecret) onSecretTrigger() },
                        onTap = { onClick() },
                    )
                },
        colors = CardDefaults.cardColors(containerColor = note.color),
        shape = RoundedCornerShape(8.dp),
        border = if (note.color == Color.White) BorderStroke(1.dp, Color(0xFFE0E0E0)) else null,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (note.title.isNotEmpty()) {
                Text(
                    note.title,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                    color = Color.Black,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            Text(
                note.preview,
                fontSize = 14.sp,
                color = Color(0xFF202124),
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditor(
    note: NoteItem?,
    onBack: () -> Unit,
    onSave: (String, String, Color) -> Unit,
    onDelete: () -> Unit,
    onArchive: () -> Unit,
) {
    var title by remember { mutableStateOf(note?.title ?: "") }
    var content by remember { mutableStateOf(note?.preview ?: "") }
    var selectedColor by remember { mutableStateOf(note?.color ?: NoteColors[0]) }
    var showColorPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { },
                navigationIcon = {
                    IconButton(onClick = { onSave(title, content, selectedColor) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color(0xFF5F6368))
                    }
                },
                actions = {
                    IconButton(onClick = { /* Pin */ }) {
                        Icon(Icons.Default.PushPin, contentDescription = "Pin", tint = Color(0xFF5F6368))
                    }
                    IconButton(onClick = { /* Reminder */ }) {
                        Icon(Icons.Default.NotificationsNone, contentDescription = "Reminder", tint = Color(0xFF5F6368))
                    }
                    IconButton(onClick = onArchive) {
                        Icon(
                            if (note?.isArchived == true) Icons.Default.Unarchive else Icons.Default.Archive,
                            contentDescription = "Archive",
                            tint = Color(0xFF5F6368),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = selectedColor),
            )
        },
        bottomBar = {
            BottomAppBar(
                containerColor = selectedColor,
                contentPadding = PaddingValues(horizontal = 8.dp),
                actions = {
                    IconButton(onClick = { showColorPicker = true }) {
                        Icon(Icons.Default.Palette, contentDescription = "Color", tint = Color(0xFF5F6368))
                    }
                    Text("Edited ${note?.date ?: "Just now"}", fontSize = 12.sp, color = Color(0xFF5F6368))
                },
                floatingActionButton = {
                    var showMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More", tint = Color(0xFF5F6368))
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                onClick = {
                                    onDelete()
                                    showMenu = false
                                },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                            )
                            DropdownMenuItem(
                                text = { Text("Make a copy") },
                                onClick = { showMenu = false },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                            )
                            DropdownMenuItem(
                                text = { Text("Send") },
                                onClick = { showMenu = false },
                                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                            )
                        }
                    }
                },
            )
        },
        containerColor = selectedColor,
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .padding(paddingValues)
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
        ) {
            BasicTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                textStyle = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Normal, color = Color.Black),
                decorationBox = { innerTextField ->
                    if (title.isEmpty()) Text("Title", fontSize = 22.sp, color = Color(0xFF5F6368))
                    innerTextField()
                },
            )
            BasicTextField(
                value = content,
                onValueChange = { content = it },
                modifier = Modifier.fillMaxSize(),
                textStyle = TextStyle(fontSize = 16.sp, color = Color(0xFF202124)),
                decorationBox = { innerTextField ->
                    if (content.isEmpty()) Text("Note", fontSize = 16.sp, color = Color(0xFF5F6368))
                    innerTextField()
                },
            )
        }

        if (showColorPicker) {
            Dialog(onDismissRequest = { showColorPicker = false }) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White,
                    modifier = Modifier.padding(16.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Background color", fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(NoteColors) { color ->
                                Box(
                                    modifier =
                                        Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(color)
                                            .border(
                                                width = if (selectedColor == color) 2.dp else 1.dp,
                                                color = if (selectedColor == color) Color(0xFF1A73E8) else Color.LightGray,
                                                shape = CircleShape,
                                            ).clickable {
                                                selectedColor = color
                                                showColorPicker = false
                                            },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
