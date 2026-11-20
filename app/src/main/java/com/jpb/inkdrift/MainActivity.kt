package com.jpb.inkdrift

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.jpb.inkdrift.util.CanvasStroke
import com.jpb.inkdrift.util.CanvasStrokePoint
import com.jpb.inkdrift.util.StorableStroke
import com.jpb.inkdrift.util.toCanvasStroke
import com.jpb.inkdrift.util.toStorable
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.hypot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val prefs = getSharedPreferences("inkdrift_prefs", Context.MODE_PRIVATE)
        val gson = Gson()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E),
                    surfaceContainerLow = Color(0xFF181818),
                    surfaceContainerHigh = Color(0xFF2C2C2C)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    FreeformAppRoot(prefs, gson)
                }
            }
        }
    }
}

// --- APP NAVIGATION & DATA MODELS ---

sealed class AppScreen {
    data object Dashboard : AppScreen()
    data class Studio(val boardId: String, val boardTitle: String) : AppScreen()
}

data class FreeformBoard(
    val id: String,
    val title: String,
    val subtitle: String
)

data class StickyNote(
    val id: String = UUID.randomUUID().toString(),
    var text: String = "Sticky note",
    var x: Float = 0f,
    var y: Float = 0f,
    var width: Float = 220f,
    var height: Float = 220f,
    val colorValue: Long = 0xFFFFEE58L // Apple Freeform style yellow
)

@Composable
fun FreeformAppRoot(prefs: android.content.SharedPreferences, gson: Gson) {
    var currentScreen by remember { mutableStateOf<AppScreen>(AppScreen.Dashboard) }

    val savedBoardsJson = prefs.getString("boards_key", null)
    val initialBoards: List<FreeformBoard> = if (!savedBoardsJson.isNullOrEmpty()) {
        val type = object : com.google.gson.reflect.TypeToken<List<FreeformBoard>>() {}.type
        gson.fromJson(savedBoardsJson, type) ?: emptyList()
    } else {
        emptyList()
    }
    val boards = remember { mutableStateListOf<FreeformBoard>().apply { addAll(initialBoards) } }

    // Save/Load StorableStroke DTOs instead of raw CanvasStrokes
    val savedStrokesJson = prefs.getString("strokes_map_key", null)
    val initialStrokesMap: Map<String, List<StorableStroke>> = if (!savedStrokesJson.isNullOrEmpty()) {
        val type = object : com.google.gson.reflect.TypeToken<Map<String, List<StorableStroke>>>() {}.type
        gson.fromJson(savedStrokesJson, type) ?: emptyMap()
    } else {
        emptyMap()
    }

    val boardStrokesMap = remember {
        val map = mutableStateMapOf<String, MutableList<CanvasStroke>>()
        initialStrokesMap.forEach { (boardId, storableStrokes) ->
            map[boardId] = mutableStateListOf<CanvasStroke>().apply {
                addAll(storableStrokes.map { it.toCanvasStroke() })
            }
        }
        map
    }

    // Save/Load Sticky Notes
    val savedNotesJson = prefs.getString("notes_map_key", null)
    val initialNotesMap: Map<String, List<StickyNote>> = if (!savedNotesJson.isNullOrEmpty()) {
        val type = object : com.google.gson.reflect.TypeToken<Map<String, List<StickyNote>>>() {}.type
        gson.fromJson(savedNotesJson, type) ?: emptyMap()
    } else {
        emptyMap()
    }

    val boardNotesMap = remember {
        val map = mutableStateMapOf<String, MutableList<StickyNote>>()
        initialNotesMap.forEach { (boardId, notes) ->
            map[boardId] = mutableStateListOf<StickyNote>().apply {
                addAll(notes)
            }
        }
        map
    }

    fun saveData() {
        val editor = prefs.edit()
        editor.putString("boards_key", gson.toJson(boards.toList()))

        // Map UI strokes to primitive DTOs before saving
        val storableMap = boardStrokesMap.mapValues { entry ->
            entry.value.map { it.toStorable() }
        }
        editor.putString("strokes_map_key", gson.toJson(storableMap))

        val notesMap = boardNotesMap.mapValues { entry ->
            entry.value.toList()
        }
        editor.putString("notes_map_key", gson.toJson(notesMap))
        editor.apply()
    }

    when (val screen = currentScreen) {
        is AppScreen.Dashboard -> {
            FreeformMainDashboardScreen(
                boards = boards,
                onBoardClick = { boardId, title ->
                    currentScreen = AppScreen.Studio(boardId, title)
                },
                onCreateBoard = { newBoard ->
                    boards.add(newBoard)
                    boardStrokesMap[newBoard.id] = mutableStateListOf()
                    boardNotesMap[newBoard.id] = mutableStateListOf()
                    saveData()
                    currentScreen = AppScreen.Studio(newBoard.id, newBoard.title)
                },
                onDeleteBoard = { board ->
                    boards.remove(board)
                    boardStrokesMap.remove(board.id)
                    boardNotesMap.remove(board.id)
                    saveData()
                }
            )
        }
        is AppScreen.Studio -> {
            val activeStrokes = remember(screen.boardId) {
                boardStrokesMap.getOrPut(screen.boardId) { mutableStateListOf() }
            }
            val activeNotes = remember(screen.boardId) {
                boardNotesMap.getOrPut(screen.boardId) { mutableStateListOf() }
            }

            FreeformStudioCanvasScreen(
                boardTitle = screen.boardTitle,
                strokes = activeStrokes,
                notes = activeNotes,
                onDataChanged = { saveData() },
                onBack = {
                    saveData()
                    currentScreen = AppScreen.Dashboard
                }
            )
        }
    }
}


// --- 1. DASHBOARD SCREEN ---

enum class FreeformCategory(val label: String, val icon: ImageVector) {
    ALL("All Boards", Icons.Default.Dashboard),
    RECENTS("Recents", Icons.Default.Schedule),
    SHARED("Shared", Icons.Default.People),
    FAVORITES("Favorites", Icons.Default.Favorite),
    DELETED("Recently Deleted", Icons.Default.Delete)
}

@Composable
fun FreeformMainDashboardScreen(
    boards: List<FreeformBoard>,
    onBoardClick: (String, String) -> Unit,
    onCreateBoard: (FreeformBoard) -> Unit,
    onDeleteBoard: (FreeformBoard) -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var selectedCategory by remember { mutableStateOf(FreeformCategory.ALL) }
    var searchQuery by remember { mutableStateOf("") }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(300.dp)
                        .padding(24.dp)
                ) {
                    Text(
                        text = "Freeform",
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 24.dp)
                    )

                    FreeformCategory.entries.forEach { category ->
                        val count = if (category == FreeformCategory.ALL) boards.size else 0
                        NavigationDrawerItem(
                            icon = { Icon(category.icon, contentDescription = null) },
                            label = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(category.label)
                                    Text(count.toString(), style = MaterialTheme.typography.bodyMedium)
                                }
                            },
                            selected = selectedCategory == category,
                            onClick = {
                                selectedCategory = category
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.padding(vertical = 4.dp),
                            shape = RoundedCornerShape(16.dp)
                        )
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            shape = RoundedCornerShape(24.dp),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(0.7f).height(48.dp)
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = {
                        val newId = System.currentTimeMillis().toString()
                        onCreateBoard(
                            FreeformBoard(
                                id = newId,
                                title = "Untitled Board",
                                subtitle = "Just now"
                            )
                        )
                    },
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Board")
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 24.dp)
            ) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = selectedCategory.label, style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.height(20.dp))

                if (boards.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No boards yet. Tap '+' to create one.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 160.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(boards) { board ->
                            DashboardBoardCard(
                                board = board,
                                onClick = { onBoardClick(board.id, board.title) },
                                onDelete = { onDeleteBoard(board) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DashboardBoardCard(board: FreeformBoard, onClick: () -> Unit, onDelete: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }

    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clickable(onClick = onClick),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        ) {
            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = board.title, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(text = board.title, style = MaterialTheme.typography.titleSmall)
                        Text(text = board.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { showMenu = true }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            shape = RoundedCornerShape(16.dp)
        ) {
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                onClick = {
                    showMenu = false
                    onDelete()
                }
            )
        }
    }
}


// --- 2. INFINITE CANVAS STUDIO SCREEN & DRAWING ENGINE ---

enum class StudioTool { PAN, PEN, NOTE, SHAPE }

@Composable
fun FreeformStudioCanvasScreen(
    boardTitle: String,
    strokes: MutableList<CanvasStroke>,
    notes: MutableList<StickyNote>,
    onDataChanged: () -> Unit,
    onBack: () -> Unit
) {
    var selectedTool by remember { mutableStateOf(StudioTool.PEN) }
    val activeColor by remember { mutableStateOf(Color(0xFFFFFFFF)) }
    val baseStrokeWidth by remember { mutableFloatStateOf(12f) }

    var scale by remember { mutableFloatStateOf(1f) }
    var canvasOffset by remember { mutableStateOf(Offset.Zero) }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF181818))) {

        FreeformInteractiveCanvas(
            selectedTool = selectedTool,
            activeColor = activeColor,
            baseStrokeWidth = baseStrokeWidth,
            strokes = strokes,
            notes = notes,
            scale = scale,
            canvasOffset = canvasOffset,
            onTransform = { pan, zoom, centroid ->
                val oldScale = scale
                val newScale = (scale * zoom).coerceIn(0.15f, 6.0f)
                val scaleFactor = newScale / oldScale
                canvasOffset = (canvasOffset - centroid) * scaleFactor + centroid + pan
                scale = newScale
            },
            onStrokeAdded = onDataChanged,
            onNoteChanged = onDataChanged
        )

        Surface(
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 4.dp,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                VerticalDivider(modifier = Modifier.height(24.dp))
                Text(text = boardTitle, style = MaterialTheme.typography.titleMedium)
            }
        }

        Box(modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 32.dp)) {
            Surface(
                modifier = Modifier.shadow(8.dp, RoundedCornerShape(32.dp)),
                shape = RoundedCornerShape(32.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StudioToolButton(Icons.Default.PanTool, "Pan", selectedTool == StudioTool.PAN) { selectedTool = StudioTool.PAN }
                    StudioToolButton(Icons.Default.Edit, "Pen", selectedTool == StudioTool.PEN) { selectedTool = StudioTool.PEN }
                    StudioToolButton(Icons.AutoMirrored.Filled.StickyNote2, "Note", selectedTool == StudioTool.NOTE) {
                        selectedTool = StudioTool.NOTE
                        val worldCenterX = -canvasOffset.x / scale + 400f
                        val worldCenterY = -canvasOffset.y / scale + 400f
                        notes.add(
                            StickyNote(
                                x = worldCenterX,
                                y = worldCenterY,
                                text = "Sticky note"
                            )
                        )
                        onDataChanged()
                    }
                    StudioToolButton(Icons.Default.Category, "Shape", selectedTool == StudioTool.SHAPE) { selectedTool = StudioTool.SHAPE }
                }
            }
        }
    }
}

@Composable
fun StudioToolButton(icon: ImageVector, label: String, isSelected: Boolean, onClick: () -> Unit) {
    val bg = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

    IconButton(onClick = onClick, modifier = Modifier.size(48.dp).clip(CircleShape).background(bg)) {
        Icon(icon, contentDescription = label, tint = tint)
    }
}

@Composable
fun FreeformInteractiveCanvas(
    selectedTool: StudioTool,
    activeColor: Color,
    baseStrokeWidth: Float,
    strokes: MutableList<CanvasStroke>,
    notes: MutableList<StickyNote>,
    scale: Float,
    canvasOffset: Offset,
    onTransform: (pan: Offset, zoom: Float, centroid: Offset) -> Unit,
    onStrokeAdded: () -> Unit,
    onNoteChanged: () -> Unit
) {
    val currentPoints = remember { mutableStateListOf<CanvasStrokePoint>() }
    val isPenActive = (selectedTool == StudioTool.PEN)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(selectedTool, activeColor, baseStrokeWidth) {
                if (selectedTool == StudioTool.NOTE) {
                    detectTapGestures(
                        onTap = { tapOffset ->
                            val worldPos = convertScreenToWorld(tapOffset, canvasOffset, scale)
                            notes.add(
                                StickyNote(
                                    x = worldPos.x - 110f,
                                    y = worldPos.y - 110f,
                                    text = "Sticky note"
                                )
                            )
                            onNoteChanged()
                        }
                    )
                } else {
                    detectCanvasGestures(
                        isPenActive = isPenActive,
                        getCanvasOffset = { canvasOffset },
                        getScale = { scale },
                        onTransform = onTransform,
                        onStrokeStart = { point ->
                            currentPoints.clear()
                            currentPoints.add(point)
                        },
                        onStrokeMove = { point ->
                            val last = currentPoints.lastOrNull()
                            if (last == null || (point.position - last.position).getDistance() > 1f) {
                                currentPoints.add(point)
                            }
                        },
                        onStrokeEnd = {
                            if (currentPoints.isNotEmpty()) {
                                strokes.add(CanvasStroke(points = currentPoints.toList(), color = activeColor, baseWidth = baseStrokeWidth))
                                currentPoints.clear()
                                onStrokeAdded()
                            }
                        }
                    )
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            withTransform({
                translate(left = canvasOffset.x, top = canvasOffset.y)
                scale(scaleX = scale, scaleY = scale, pivot = Offset.Zero)
            }) {
                strokes.forEach { stroke -> drawSmoothPressureStroke(stroke) }
                if (currentPoints.isNotEmpty()) {
                    drawSmoothPressureStroke(CanvasStroke(points = currentPoints, color = activeColor, baseWidth = baseStrokeWidth))
                }
            }
        }

        notes.forEach { note ->
            StickyNoteItem(
                note = note,
                scale = scale,
                canvasOffset = canvasOffset,
                onNoteChanged = onNoteChanged,
                onDelete = {
                    notes.remove(note)
                    onNoteChanged()
                }
            )
        }
    }
}

@Composable
fun StickyNoteItem(
    note: StickyNote,
    scale: Float,
    canvasOffset: Offset,
    onNoteChanged: () -> Unit,
    onDelete: () -> Unit
) {
    var text by remember(note.text) { mutableStateOf(note.text) }
    var posX by remember { mutableFloatStateOf(note.x) }
    var posY by remember { mutableFloatStateOf(note.y) }
    var isSelected by remember { mutableStateOf(false) }

    val screenX = canvasOffset.x + posX * scale
    val screenY = canvasOffset.y + posY * scale

    Box(
        modifier = Modifier
            .graphicsLayer {
                translationX = screenX
                translationY = screenY
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
            }
            .size(width = note.width.dp, height = note.height.dp)
            .shadow(8.dp, RoundedCornerShape(4.dp))
            .background(Color(note.colorValue), RoundedCornerShape(4.dp))
            .clickable { isSelected = !isSelected }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    posX += dragAmount.x / scale
                    posY += dragAmount.y / scale
                    note.x = posX
                    note.y = posY
                    onNoteChanged()
                }
            }
            .padding(12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                if (isSelected) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Delete Note",
                            tint = Color.DarkGray,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            BasicTextField(
                value = text,
                onValueChange = {
                    text = it
                    note.text = it
                    onNoteChanged()
                },
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = Color(0xFF212121)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }

        // 4 Blue corner dots (selection handles like Apple Freeform)
        if (isSelected) {
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .align(Alignment.TopStart)
                        .offset((-4).dp, (-4).dp)
                        .background(Color(0xFF2196F3), CircleShape)
                )
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .align(Alignment.TopEnd)
                        .offset(4.dp, (-4).dp)
                        .background(Color(0xFF2196F3), CircleShape)
                )
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .align(Alignment.BottomStart)
                        .offset((-4).dp, 4.dp)
                        .background(Color(0xFF2196F3), CircleShape)
                )
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .align(Alignment.BottomEnd)
                        .offset(4.dp, 4.dp)
                        .background(Color(0xFF2196F3), CircleShape)
                )
            }
        }
    }
}

fun convertScreenToWorld(screenOffset: Offset, canvasOffset: Offset, scale: Float): Offset {
    return (screenOffset - canvasOffset) / scale
}

suspend fun PointerInputScope.detectCanvasGestures(
    isPenActive: Boolean,
    getCanvasOffset: () -> Offset,
    getScale: () -> Float,
    onTransform: (pan: Offset, zoom: Float, centroid: Offset) -> Unit,
    onStrokeStart: (CanvasStrokePoint) -> Unit,
    onStrokeMove: (CanvasStrokePoint) -> Unit,
    onStrokeEnd: () -> Unit
) {
    awaitEachGesture {
        val firstDown = awaitFirstDown(requireUnconsumed = false)
        var isDrawing = false

        if (isPenActive) {
            isDrawing = true
            val worldPos = convertScreenToWorld(firstDown.position, getCanvasOffset(), getScale())
            onStrokeStart(CanvasStrokePoint(worldPos, firstDown.pressure.coerceIn(0.1f, 1.0f)))
            firstDown.consume()
        }

        do {
            val event = awaitPointerEvent()
            val activePointers = event.changes.filter { it.pressed }

            if (activePointers.size > 1) {
                if (isDrawing) {
                    isDrawing = false
                    onStrokeEnd()
                }
                val zoom = event.calculateZoom()
                val pan = event.calculatePan()
                val centroid = activePointers.map { it.position }.reduce { acc, offset -> acc + offset } / activePointers.size.toFloat()
                onTransform(pan, zoom, centroid)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            } else if (activePointers.size == 1) {
                val change = activePointers.first()
                if (isDrawing) {
                    change.historical.forEach { historical ->
                        val histWorldPos = convertScreenToWorld(historical.position, getCanvasOffset(), getScale())
                        onStrokeMove(CanvasStrokePoint(histWorldPos, change.pressure.coerceIn(0.1f, 1.0f)))
                    }
                    val worldPos = convertScreenToWorld(change.position, getCanvasOffset(), getScale())
                    onStrokeMove(CanvasStrokePoint(worldPos, change.pressure.coerceIn(0.1f, 1.0f)))
                    change.consume()
                } else if (!isPenActive) {
                    val pan = event.calculatePan()
                    onTransform(pan, 1f, change.position)
                    change.consume()
                }
            }
        } while (event.changes.any { it.pressed })

        if (isDrawing) onStrokeEnd()
    }
}

fun DrawScope.drawSmoothPressureStroke(stroke: CanvasStroke) {
    val points = stroke.points
    if (points.isEmpty()) return

    if (points.size == 1) {
        drawCircle(color = stroke.color, radius = (stroke.baseWidth * points[0].pressure) / 2f, center = points[0].position)
        return
    }

    var prevMid = points[0].position
    var prevPressure = points[0].pressure

    for (i in 1 until points.size) {
        val current = points[i]
        val nextMid = if (i == points.size - 1) current.position else androidx.compose.ui.geometry.lerp(points[i - 1].position, current.position, 0.5f)
        val nextPressure = if (i == points.size - 1) current.pressure else (points[i - 1].pressure + current.pressure) / 2f

        drawQuadraticPressureSegment(prevMid, points[i - 1].position, nextMid, prevPressure, nextPressure, stroke.baseWidth, stroke.color)
        prevMid = nextMid
        prevPressure = nextPressure
    }
}

private fun DrawScope.drawQuadraticPressureSegment(
    p0: Offset, p1: Offset, p2: Offset, pressure0: Float, pressure2: Float, baseWidth: Float, color: Color
) {
    val dist = hypot(p1.x - p0.x, p1.y - p0.y) + hypot(p2.x - p1.x, p2.y - p1.y)
    val steps = ceil(dist / 1.5f).toInt().coerceAtLeast(1)

    for (step in 0..steps) {
        val t = step.toFloat() / steps
        val invT = 1f - t
        val x = (invT * invT * p0.x) + (2f * invT * t * p1.x) + (t * t * p2.x)
        val y = (invT * invT * p0.y) + (2f * invT * t * p1.y) + (t * t * p2.y)
        val interpolatedPressure = pressure0 + (pressure2 - pressure0) * t
        drawCircle(color = color, radius = ((baseWidth * interpolatedPressure) / 2f).coerceAtLeast(0.5f), center = Offset(x, y))
    }
}
