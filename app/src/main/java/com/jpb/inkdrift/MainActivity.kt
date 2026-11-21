package com.jpb.inkdrift

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

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

    // Save/Load Shapes
    val boardShapesMap = remember {
        val map = mutableStateMapOf<String, MutableList<BoardShape>>()
        val json = prefs.getString("shapes_map_key", null)
        if (!json.isNullOrEmpty()) {
            val type = object : TypeToken<Map<String, List<BoardShape>>>() {}.type
            val saved: Map<String, List<BoardShape>>? = gson.fromJson(json, type)
            saved?.forEach { (boardId, list) ->
                map[boardId] = mutableStateListOf<BoardShape>().apply { addAll(list) }
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

        val shapesMap = boardShapesMap.mapValues { entry ->
            entry.value.toList()
        }
        editor.putString("shapes_map_key", gson.toJson(shapesMap))
        editor.apply()
    }

    when (val screen = currentScreen) {
        is AppScreen.Dashboard -> {
            FreeformMainDashboardScreen(
                boards = boards,
                boardStrokesMap = boardStrokesMap,
                boardNotesMap = boardNotesMap,
                boardShapesMap = boardShapesMap,
                onBoardClick = { boardId, title ->
                    currentScreen = AppScreen.Studio(boardId, title)
                },
                onCreateBoard = { newBoard ->
                    boards.add(newBoard)
                    boardStrokesMap[newBoard.id] = mutableStateListOf()
                    boardNotesMap[newBoard.id] = mutableStateListOf()
                    boardShapesMap[newBoard.id] = mutableStateListOf()
                    saveData()
                    currentScreen = AppScreen.Studio(newBoard.id, newBoard.title)
                },
                onRenameBoard = { board, newTitle ->
                    val index = boards.indexOf(board)
                    if (index != -1) {
                        boards[index] = board.copy(title = newTitle)
                        saveData()
                    }
                },
                onDeleteBoard = { board ->
                    boards.remove(board)
                    boardStrokesMap.remove(board.id)
                    boardNotesMap.remove(board.id)
                    boardShapesMap.remove(board.id)
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
            val activeShapes = remember(screen.boardId) {
                boardShapesMap.getOrPut(screen.boardId) { mutableStateListOf() }
            }

            FreeformStudioCanvasScreen(
                boardTitle = screen.boardTitle,
                strokes = activeStrokes,
                notes = activeNotes,
                shapes = activeShapes,
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
    ALL("All Boards", Icons.Default.Dashboard)
}

@Composable
fun FreeformMainDashboardScreen(
    boards: List<FreeformBoard>,
    boardStrokesMap: Map<String, List<CanvasStroke>>,
    boardNotesMap: Map<String, List<StickyNote>>,
    boardShapesMap: Map<String, List<BoardShape>>,
    onBoardClick: (String, String) -> Unit,
    onCreateBoard: (FreeformBoard) -> Unit,
    onRenameBoard: (FreeformBoard, String) -> Unit,
    onDeleteBoard: (FreeformBoard) -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var selectedCategory by remember { mutableStateOf(FreeformCategory.ALL) }
    var searchQuery by remember { mutableStateOf("") }
    var showCreateDialog by remember { mutableStateOf(false) }
    var newBoardTitleInput by remember { mutableStateOf("") }

    val filteredBoards = if (searchQuery.isBlank()) {
        boards
    } else {
        boards.filter { it.title.contains(searchQuery.trim(), ignoreCase = true) }
    }

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
                        text = "InkDrift",
                        style = MaterialTheme.typography.headlineLargeEmphasized,
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
                            placeholder = { Text("Search", style = MaterialTheme.typography.bodyMedium) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear search")
                                    }
                                }
                            },
                            shape = RoundedCornerShape(24.dp),
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth(0.7f)
                                .defaultMinSize(minHeight = 44.dp)
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
                        newBoardTitleInput = ""
                        showCreateDialog = true
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
                } else if (filteredBoards.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No boards matching \"$searchQuery\"",
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
                        items(filteredBoards, key = { it.id }) { board ->
                            DashboardBoardCard(
                                board = board,
                                strokes = boardStrokesMap[board.id] ?: emptyList(),
                                notes = boardNotesMap[board.id] ?: emptyList(),
                                shapes = boardShapesMap[board.id] ?: emptyList(),
                                onClick = { onBoardClick(board.id, board.title) },
                                onRename = { newTitle -> onRenameBoard(board, newTitle) },
                                onDelete = { onDeleteBoard(board) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Create New Board") },
            text = {
                OutlinedTextField(
                    value = newBoardTitleInput,
                    onValueChange = { newBoardTitleInput = it },
                    singleLine = true,
                    label = { Text("Board Title") },
                    placeholder = { Text("e.g. Design Ideas") },
                    shape = RoundedCornerShape(12.dp)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCreateDialog = false
                        val title = newBoardTitleInput.ifBlank { "Untitled Board" }
                        val newId = System.currentTimeMillis().toString()
                        onCreateBoard(
                            FreeformBoard(
                                id = newId,
                                title = title,
                                subtitle = "Just now"
                            )
                        )
                    }
                ) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel")
                }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }
}

@Composable
fun DashboardBoardCard(
    board: FreeformBoard,
    strokes: List<CanvasStroke>,
    notes: List<StickyNote>,
    shapes: List<BoardShape>,
    onClick: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf(board.title) }

    Box {
        Card(
            onClick = onClick,
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                        .background(Color(0xFF1E1E1E))
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawBoardThumbnail(strokes, notes, shapes)
                    }
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
                text = { Text("Rename") },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                onClick = {
                    showMenu = false
                    renameText = board.title
                    showRenameDialog = true
                }
            )
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                onClick = {
                    showMenu = false
                    onDelete()
                }
            )
        }

        if (showRenameDialog) {
            AlertDialog(
                onDismissRequest = { showRenameDialog = false },
                title = { Text("Rename Board") },
                text = {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true,
                        label = { Text("Board Title") },
                        shape = RoundedCornerShape(12.dp)
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showRenameDialog = false
                            if (renameText.isNotBlank()) {
                                onRename(renameText.trim())
                            }
                        }
                    ) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showRenameDialog = false }) {
                        Text("Cancel")
                    }
                },
                shape = RoundedCornerShape(24.dp)
            )
        }
    }
}

fun DrawScope.drawBoardThumbnail(
    strokes: List<CanvasStroke>,
    notes: List<StickyNote>,
    shapes: List<BoardShape>
) {
    if (strokes.isEmpty() && notes.isEmpty() && shapes.isEmpty()) {
        drawRect(Color(0xFF1A1A1A))
        return
    }

    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE

    shapes.forEach { shape ->
        minX = minOf(minX, shape.x)
        minY = minOf(minY, shape.y)
        maxX = maxOf(maxX, shape.x + shape.width)
        maxY = maxOf(maxY, shape.y + shape.height)
    }

    notes.forEach { note ->
        minX = minOf(minX, note.x)
        minY = minOf(minY, note.y)
        maxX = maxOf(maxX, note.x + note.width)
        maxY = maxOf(maxY, note.y + note.height)
    }

    strokes.forEach { stroke ->
        stroke.points.forEach { pt ->
            minX = minOf(minX, pt.position.x)
            minY = minOf(minY, pt.position.y)
            maxX = maxOf(maxX, pt.position.x)
            maxY = maxOf(maxY, pt.position.y)
        }
    }

    if (minX >= maxX || minY >= maxY) {
        minX = 0f
        minY = 0f
        maxX = 800f
        maxY = 600f
    }

    val contentWidth = (maxX - minX).coerceAtLeast(100f)
    val contentHeight = (maxY - minY).coerceAtLeast(100f)
    val padding = 30f

    val scaleX = size.width / (contentWidth + padding * 2)
    val scaleY = size.height / (contentHeight + padding * 2)
    val thumbnailScale = minOf(scaleX, scaleY).coerceAtMost(1.0f)

    val contentCenterX = minX + contentWidth / 2f
    val contentCenterY = minY + contentHeight / 2f

    val thumbnailOffsetX = size.width / 2f - contentCenterX * thumbnailScale
    val thumbnailOffsetY = size.height / 2f - contentCenterY * thumbnailScale

    withTransform({
        translate(left = thumbnailOffsetX, top = thumbnailOffsetY)
        scale(scaleX = thumbnailScale, scaleY = thumbnailScale, pivot = Offset.Zero)
    }) {
        shapes.forEach { shape -> drawBoardShape(shape) }
        strokes.forEach { stroke -> drawSmoothPressureStroke(stroke) }
        notes.forEach { note ->
            drawRoundRect(
                color = Color(note.colorValue),
                topLeft = Offset(note.x, note.y),
                size = Size(note.width, note.height),
                cornerRadius = CornerRadius(4f, 4f)
            )
        }
    }
}


// --- 2. INFINITE CANVAS STUDIO SCREEN & DRAWING ENGINE ---

enum class StudioTool { PAN, PEN, ERASER, NOTE }

@Composable
fun FreeformStudioCanvasScreen(
    boardTitle: String,
    strokes: MutableList<CanvasStroke>,
    notes: MutableList<StickyNote>,
    shapes: MutableList<BoardShape>,
    onDataChanged: () -> Unit,
    onBack: () -> Unit
) {
    var selectedTool by remember { mutableStateOf(StudioTool.PEN) }
    var activeColor by remember { mutableStateOf(Color(0xFFFFFFFF)) }
    var showPenColors by remember { mutableStateOf(false) }
    val baseStrokeWidth by remember { mutableFloatStateOf(12f) }

    var scale by remember { mutableFloatStateOf(1f) }
    var canvasOffset by remember { mutableStateOf(Offset.Zero) }

    var showShapePicker by remember { mutableStateOf(false) }
    var selectedShapeId by remember { mutableStateOf<String?>(null) }
    var selectedNoteId by remember { mutableStateOf<String?>(null) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current

    // Shapes can only be selected while the select/pan tool is active.
    LaunchedEffect(selectedTool) {
        if (selectedTool != StudioTool.PAN) {
            selectedShapeId = null
            selectedNoteId = null
        }
    }

    fun insertShape(type: ShapeType) {
        val viewportCenter = convertScreenToWorld(
            Offset(viewportSize.width / 2f, viewportSize.height / 2f),
            canvasOffset,
            scale
        )
        // ~160dp on screen at the current zoom level, whatever the zoom is.
        val base = with(density) { 160.dp.toPx() } / scale
        val newShape = if (type.isLine) {
            BoardShape(
                type = type,
                x = viewportCenter.x - base * 0.75f,
                y = viewportCenter.y,
                width = base * 1.5f,
                height = 0f
            )
        } else {
            val w = base * sqrt(type.aspect)
            val h = base / sqrt(type.aspect)
            BoardShape(
                type = type,
                x = viewportCenter.x - w / 2f,
                y = viewportCenter.y - h / 2f,
                width = w,
                height = h
            )
        }
        shapes.add(newShape)
        selectedTool = StudioTool.PAN
        selectedShapeId = newShape.id
        selectedNoteId = null
        showShapePicker = false
        onDataChanged()
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF181818))) {

        FreeformInteractiveCanvas(
            selectedTool = selectedTool,
            activeColor = activeColor,
            baseStrokeWidth = baseStrokeWidth,
            strokes = strokes,
            notes = notes,
            shapes = shapes,
            selectedShapeId = selectedShapeId,
            onSelectShape = { selectedShapeId = it },
            selectedNoteId = selectedNoteId,
            onSelectNote = { selectedNoteId = it },
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
            onNoteChanged = onDataChanged,
            onShapesChanged = onDataChanged,
            onViewportChanged = { viewportSize = it }
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

        if (showShapePicker) {
            // Tap outside the picker to dismiss it (sits below the toolbar so the toolbar stays usable).
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showShapePicker = false }
            )
            ShapePickerPopup(
                onPick = { insertShape(it) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 108.dp)
            )
        }

        if (showPenColors && selectedTool == StudioTool.PEN) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showPenColors = false }
            )
            PenColorPopup(
                selected = activeColor,
                onPick = {
                    activeColor = it
                    showPenColors = false
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 108.dp)
            )
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
                    // PAN doubles as the "select & move" tool: it pans the canvas and
                    // selects / moves / resizes / rotates shapes.
                    StudioToolButton(Icons.Default.PanTool, "Select", selectedTool == StudioTool.PAN) {
                        selectedTool = StudioTool.PAN
                        showShapePicker = false
                        showPenColors = false
                    }
                    // Tap Pen once to select it, tap again to pick a colour.
                    StudioToolButton(Icons.Default.Edit, "Pen", selectedTool == StudioTool.PEN, dotColor = activeColor) {
                        if (selectedTool == StudioTool.PEN) {
                            showPenColors = !showPenColors
                        } else {
                            selectedTool = StudioTool.PEN
                            showPenColors = false
                        }
                        showShapePicker = false
                    }
                    StudioToolButton(EraserIcon, "Eraser", selectedTool == StudioTool.ERASER) {
                        selectedTool = StudioTool.ERASER
                        showShapePicker = false
                        showPenColors = false
                    }
                    // Adds a note in the middle of the screen and selects it, ready to move / resize / recolour.
                    StudioToolButton(Icons.AutoMirrored.Filled.StickyNote2, "Note", false) {
                        showShapePicker = false
                        showPenColors = false
                        val center = convertScreenToWorld(
                            Offset(viewportSize.width / 2f, viewportSize.height / 2f),
                            canvasOffset,
                            scale
                        )
                        val sizeDp = 220f / scale // looks ~220dp on screen at the current zoom
                        val halfPx = with(density) { (sizeDp / 2f).dp.toPx() }
                        val note = StickyNote(
                            x = center.x - halfPx,
                            y = center.y - halfPx,
                            width = sizeDp,
                            height = sizeDp,
                            text = "Sticky note"
                        )
                        notes.add(note)
                        selectedTool = StudioTool.PAN
                        selectedShapeId = null
                        selectedNoteId = note.id
                        onDataChanged()
                    }
                    StudioToolButton(Icons.Default.Category, "Shapes", showShapePicker) {
                        showShapePicker = !showShapePicker
                        showPenColors = false
                    }
                }
            }
        }
    }
}

@Composable
fun StudioToolButton(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    dotColor: Color? = null,
    onClick: () -> Unit
) {
    val bg = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

    Box {
        IconButton(onClick = onClick, modifier = Modifier.size(48.dp).clip(CircleShape).background(bg)) {
            Icon(icon, contentDescription = label, tint = tint)
        }
        if (dotColor != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(12.dp)
                    .background(dotColor, CircleShape)
                    .border(1.dp, Color.Black.copy(alpha = 0.4f), CircleShape)
            )
        }
    }
}

@Composable
fun FreeformInteractiveCanvas(
    selectedTool: StudioTool,
    activeColor: Color,
    baseStrokeWidth: Float,
    strokes: MutableList<CanvasStroke>,
    notes: MutableList<StickyNote>,
    shapes: MutableList<BoardShape>,
    selectedShapeId: String?,
    onSelectShape: (String?) -> Unit,
    selectedNoteId: String?,
    onSelectNote: (String?) -> Unit,
    scale: Float,
    canvasOffset: Offset,
    onTransform: (pan: Offset, zoom: Float, centroid: Offset) -> Unit,
    onStrokeAdded: () -> Unit,
    onNoteChanged: () -> Unit,
    onShapesChanged: () -> Unit,
    onViewportChanged: (IntSize) -> Unit
) {
    val density = LocalDensity.current

    // The pointerInput block below is only restarted when its keys change, so anything
    // that changes while a gesture block is alive has to be read through updated state.
    val currentScale by rememberUpdatedState(scale)
    val currentOffset by rememberUpdatedState(canvasOffset)
    val currentSelectedId by rememberUpdatedState(selectedShapeId)
    val currentOnSelect by rememberUpdatedState(onSelectShape)
    val currentOnShapesChanged by rememberUpdatedState(onShapesChanged)
    val currentSelectedNoteId by rememberUpdatedState(selectedNoteId)
    val currentOnSelectNote by rememberUpdatedState(onSelectNote)
    val currentOnNoteChanged by rememberUpdatedState(onNoteChanged)

    val currentPoints = remember { mutableStateListOf<CanvasStrokePoint>() }
    val isPenActive = (selectedTool == StudioTool.PEN)
    val isEraserActive = (selectedTool == StudioTool.ERASER)
    var selectedStroke by remember { mutableStateOf<CanvasStroke?>(null) }
    val erasedSomething = remember { booleanArrayOf(false) }
    LaunchedEffect(selectedTool) { selectedStroke = null }
    val shapesInteractive = (selectedTool == StudioTool.PAN)
    var drag by remember { mutableStateOf<ShapeDragState?>(null) }
    var noteDrag by remember { mutableStateOf<NoteDragState?>(null) }

    // Outer box only lays things out. The gesture surface (canvas + notes) is a separate child, and the
    // floating action bars are its siblings, so touches on the bars never reach the canvas gesture handler.
    Box(modifier = Modifier.fillMaxSize().onSizeChanged { onViewportChanged(it) }) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(selectedTool, activeColor, baseStrokeWidth) {
                    if (selectedTool == StudioTool.NOTE) {
                        detectTapGestures(
                            onTap = { tapOffset ->
                                val worldPos = convertScreenToWorld(tapOffset, currentOffset, currentScale)
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
                            isEraserActive = isEraserActive,
                            getCanvasOffset = { currentOffset },
                            getScale = { currentScale },
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
                            },
                            onObjectTouchDown = { world ->
                                if (!shapesInteractive) {
                                    false
                                } else {
                                    // Sticky notes sit above shapes, so they get the first chance to grab the touch.
                                    val noteStarted = beginNoteDrag(notes, currentSelectedNoteId, world, currentScale, density)
                                    if (noteStarted != null) {
                                        currentOnSelectNote(noteStarted.start.id)
                                        currentOnSelect(null)
                                        selectedStroke = null
                                        noteDrag = noteStarted
                                        true
                                    } else {
                                        val started = beginShapeDrag(shapes, currentSelectedId, world, currentScale, density)
                                        if (started != null) {
                                            currentOnSelect(started.start.id)
                                            currentOnSelectNote(null)
                                            selectedStroke = null
                                            drag = started
                                            true
                                        } else {
                                            false
                                        }
                                    }
                                }
                            },
                            onObjectDrag = { world ->
                                val nd = noteDrag
                                if (nd != null) {
                                    val index = notes.indexOfFirst { it.id == nd.start.id }
                                    if (index >= 0) notes[index] = applyNoteDrag(nd, world, density)
                                }
                                drag?.let { d ->
                                    val index = shapes.indexOfFirst { it.id == d.start.id }
                                    if (index >= 0) {
                                        val minSize = with(density) { 28.dp.toPx() } / currentScale
                                        shapes[index] = applyShapeDrag(d, world, minSize)
                                    }
                                }
                            },
                            onObjectTouchUp = { moved ->
                                val finished = drag
                                val finishedNote = noteDrag
                                drag = null
                                noteDrag = null
                                // Persist once per gesture instead of on every move event.
                                if (moved && finished != null) currentOnShapesChanged()
                                if (moved && finishedNote != null) currentOnNoteChanged()
                            },
                            onEmptyTap = { world ->
                                // Tap on a pen stroke selects it (so it can be recoloured / deleted).
                                val tolerance = with(density) { 14.dp.toPx() } / currentScale
                                selectedStroke = strokes.lastOrNull { it.hitTest(world, tolerance) }
                                currentOnSelect(null)
                                currentOnSelectNote(null)
                            },
                            onErase = { world ->
                                val tolerance = with(density) { 12.dp.toPx() } / currentScale
                                if (strokes.removeAll { it.hitTest(world, tolerance) }) erasedSomething[0] = true
                            },
                            onEraseEnd = {
                                // Persist once per erase gesture.
                                if (erasedSomething[0]) {
                                    erasedSomething[0] = false
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
                    shapes.forEach { shape -> drawBoardShape(shape) }
                    selectedStroke?.let { sel ->
                        drawSmoothPressureStroke(
                            CanvasStroke(points = sel.points, color = Color(0x802196F3), baseWidth = sel.baseWidth + 14f)
                        )
                    }
                    strokes.forEach { stroke -> drawSmoothPressureStroke(stroke) }
                    if (currentPoints.isNotEmpty()) {
                        drawSmoothPressureStroke(CanvasStroke(points = currentPoints, color = activeColor, baseWidth = baseStrokeWidth))
                    }
                    if (shapesInteractive) {
                        shapes.firstOrNull { it.id == selectedShapeId }?.let { drawSelectionOverlay(it, scale) }
                    }
                }
            }

            notes.forEach { note ->
                key(note.id) {
                    StickyNoteItem(
                        note = note,
                        scale = scale,
                        canvasOffset = canvasOffset,
                        isSelected = shapesInteractive && note.id == selectedNoteId,
                        onTextChange = { newText ->
                            val i = notes.indexOfFirst { it.id == note.id }
                            if (i >= 0) {
                                notes[i] = notes[i].copy(text = newText)
                                onNoteChanged()
                            }
                        }
                    )
                }
            }

        } // end of gesture surface

        // Floating action bar above the selected sticky note.
        val noteSel = if (shapesInteractive && noteDrag == null) notes.firstOrNull { it.id == selectedNoteId } else null
        if (noteSel != null) {
            val noteW = with(density) { noteSel.width.dp.toPx() }
            val noteH = with(density) { noteSel.height.dp.toPx() }
            val topLeft = Offset(noteSel.x, noteSel.y) * scale + canvasOffset
            val bottomRight = Offset(noteSel.x + noteW, noteSel.y + noteH) * scale + canvasOffset
            ShapeContextBar(
                shapeId = noteSel.id,
                centerX = (topLeft.x + bottomRight.x) / 2f,
                topY = topLeft.y,
                bottomY = bottomRight.y,
                gapPx = with(density) { 16.dp.toPx() },
                minTopPx = with(density) { 120.dp.toPx() },
                currentColor = noteSel.colorValue,
                palette = NOTE_PALETTE,
                onColor = { c ->
                    val i = notes.indexOfFirst { it.id == noteSel.id }
                    if (i >= 0) {
                        notes[i] = notes[i].copy(colorValue = c)
                        onNoteChanged()
                    }
                },
                onDuplicate = {
                    val latest = notes.firstOrNull { it.id == noteSel.id }
                    if (latest != null) {
                        val nudge = with(density) { 24.dp.toPx() } / scale
                        val copy = latest.copy(id = UUID.randomUUID().toString(), x = latest.x + nudge, y = latest.y + nudge)
                        notes.add(copy)
                        onSelectNote(copy.id)
                        onNoteChanged()
                    }
                },
                onBringToFront = {
                    val latest = notes.firstOrNull { it.id == noteSel.id }
                    if (latest != null) {
                        notes.removeAll { it.id == latest.id }
                        notes.add(latest)
                        onNoteChanged()
                    }
                },
                onDelete = {
                    notes.removeAll { it.id == noteSel.id }
                    onSelectNote(null)
                    onNoteChanged()
                }
            )
        }

        // Floating action bar above the selected pen stroke.
        val strokeSel = if (shapesInteractive && drag == null) selectedStroke else null
        if (strokeSel != null && strokeSel.points.isNotEmpty()) {
            val strokeScreenPts = strokeSel.points.map { it.position * scale + canvasOffset }
            ShapeContextBar(
                shapeId = "stroke-" + System.identityHashCode(strokeSel),
                centerX = (strokeScreenPts.minOf { it.x } + strokeScreenPts.maxOf { it.x }) / 2f,
                topY = strokeScreenPts.minOf { it.y },
                bottomY = strokeScreenPts.maxOf { it.y },
                gapPx = with(density) { 40.dp.toPx() },
                minTopPx = with(density) { 120.dp.toPx() },
                currentColor = strokeSel.color.toArgb().toLong() and 0xFFFFFFFFL,
                onColor = { c ->
                    val i = strokes.indexOfFirst { it === strokeSel }
                    if (i >= 0) {
                        val recoloured = CanvasStroke(points = strokeSel.points, color = Color(c), baseWidth = strokeSel.baseWidth)
                        strokes[i] = recoloured
                        selectedStroke = recoloured
                        onStrokeAdded()
                    }
                },
                onDuplicate = null,
                onBringToFront = null,
                onDelete = {
                    strokes.removeAll { it === strokeSel }
                    selectedStroke = null
                    onStrokeAdded()
                }
            )
        }

        // Floating action bar above the selected shape (hidden while dragging).
        val selectedShape = if (shapesInteractive && drag == null) {
            shapes.firstOrNull { it.id == selectedShapeId }
        } else {
            null
        }
        if (selectedShape != null) {
            val screenPoints = selectedShape.outlinePoints().map { it * scale + canvasOffset }
            ShapeContextBar(
                shapeId = selectedShape.id,
                centerX = (screenPoints.minOf { it.x } + screenPoints.maxOf { it.x }) / 2f,
                topY = screenPoints.minOf { it.y },
                bottomY = screenPoints.maxOf { it.y },
                gapPx = with(density) { 56.dp.toPx() },
                minTopPx = with(density) { 120.dp.toPx() },
                currentColor = selectedShape.colorValue,
                onColor = { color ->
                    val i = shapes.indexOfFirst { it.id == selectedShape.id }
                    if (i >= 0) {
                        shapes[i] = shapes[i].copy(colorValue = color)
                        onShapesChanged()
                    }
                },
                onDuplicate = {
                    val latest = shapes.firstOrNull { it.id == selectedShape.id }
                    if (latest != null) {
                        val nudge = with(density) { 24.dp.toPx() } / scale
                        val copy = latest.copy(
                            id = UUID.randomUUID().toString(),
                            x = latest.x + nudge,
                            y = latest.y + nudge
                        )
                        shapes.add(copy)
                        onSelectShape(copy.id)
                        onShapesChanged()
                    }
                },
                onBringToFront = {
                    val latest = shapes.firstOrNull { it.id == selectedShape.id }
                    if (latest != null) {
                        shapes.removeAll { it.id == latest.id }
                        shapes.add(latest)
                        onShapesChanged()
                    }
                },
                onDelete = {
                    shapes.removeAll { it.id == selectedShape.id }
                    onSelectShape(null)
                    onShapesChanged()
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
    isSelected: Boolean,
    onTextChange: (String) -> Unit
) {
    // Moving / resizing / selecting is handled by the canvas gesture handler (see beginNoteDrag);
    // this composable only draws the note and owns the text field.
    var text by remember { mutableStateOf(note.text) }

    val screenX = canvasOffset.x + note.x * scale
    val screenY = canvasOffset.y + note.y * scale
    val blue = Color(0xFF2196F3)

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
            .then(if (isSelected) Modifier.border(2.dp, blue, RoundedCornerShape(4.dp)) else Modifier)
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            BasicTextField(
                value = text,
                onValueChange = {
                    text = it
                    onTextChange(it)
                },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color(0xFF212121)),
                modifier = Modifier.fillMaxSize()
            )
        }

        // Corner resize handles (visual only; the canvas gesture handler does the actual resizing).
        if (isSelected) {
            val alignments = listOf(Alignment.TopStart, Alignment.TopEnd, Alignment.BottomStart, Alignment.BottomEnd)
            val offsets = listOf((-7).dp to (-7).dp, 7.dp to (-7).dp, (-7).dp to 7.dp, 7.dp to 7.dp)
            alignments.forEachIndexed { i, alignment ->
                Box(
                    modifier = Modifier
                        .align(alignment)
                        .offset(offsets[i].first, offsets[i].second)
                        .size(14.dp)
                        .background(Color.White, CircleShape)
                        .padding(2.dp)
                        .background(blue, CircleShape)
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
    onStrokeEnd: () -> Unit,
    isEraserActive: Boolean = false,
    // Called for every finger position (world coordinates) while the eraser is down.
    onErase: (world: Offset) -> Unit = {},
    onEraseEnd: () -> Unit = {},
    // Return true from onObjectTouchDown when the touch grabbed a canvas object (e.g. a shape).
    onObjectTouchDown: (world: Offset) -> Boolean = { false },
    onObjectDrag: (world: Offset) -> Unit = {},
    onObjectTouchUp: (moved: Boolean) -> Unit = {},
    onEmptyTap: (world: Offset) -> Unit = {}
) {
    awaitEachGesture {
        val firstDown = awaitFirstDown(requireUnconsumed = false)
        val slop = viewConfiguration.touchSlop
        val downPosition = firstDown.position
        var isDrawing = false
        var isErasing = false
        var isDraggingObject = false
        var objectMoved = false
        var canvasMoved = false

        if (isPenActive) {
            isDrawing = true
            val worldPos = convertScreenToWorld(firstDown.position, getCanvasOffset(), getScale())
            onStrokeStart(CanvasStrokePoint(worldPos, firstDown.pressure.coerceIn(0.1f, 1.0f)))
            firstDown.consume()
        } else if (isEraserActive) {
            isErasing = true
            onErase(convertScreenToWorld(downPosition, getCanvasOffset(), getScale()))
            firstDown.consume()
        } else if (onObjectTouchDown(convertScreenToWorld(downPosition, getCanvasOffset(), getScale()))) {
            isDraggingObject = true
            firstDown.consume()
        }

        do {
            val event = awaitPointerEvent()
            val activePointers = event.changes.filter { it.pressed }

            if (activePointers.size > 1) {
                canvasMoved = true
                if (isDrawing) {
                    isDrawing = false
                    onStrokeEnd()
                }
                if (isErasing) {
                    isErasing = false
                    onEraseEnd()
                }
                if (isDraggingObject) {
                    isDraggingObject = false
                    onObjectTouchUp(objectMoved)
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
                } else if (isErasing) {
                    change.historical.forEach { historical ->
                        onErase(convertScreenToWorld(historical.position, getCanvasOffset(), getScale()))
                    }
                    onErase(convertScreenToWorld(change.position, getCanvasOffset(), getScale()))
                    change.consume()
                } else if (isDraggingObject) {
                    if (!objectMoved && (change.position - downPosition).getDistance() > slop) objectMoved = true
                    if (objectMoved) {
                        onObjectDrag(convertScreenToWorld(change.position, getCanvasOffset(), getScale()))
                    }
                    change.consume()
                } else if (!isPenActive && !isEraserActive) {
                    if ((change.position - downPosition).getDistance() > slop) canvasMoved = true
                    val pan = event.calculatePan()
                    onTransform(pan, 1f, change.position)
                    change.consume()
                }
            }
        } while (event.changes.any { it.pressed })

        if (isDrawing) onStrokeEnd()
        if (isErasing) onEraseEnd()
        if (isDraggingObject) {
            onObjectTouchUp(objectMoved)
        } else if (!isPenActive && !isEraserActive && !canvasMoved) {
            onEmptyTap(convertScreenToWorld(downPosition, getCanvasOffset(), getScale()))
        }
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

// --- 3. SHAPES (Apple Freeform style: picker, insert, select, move, resize, rotate) ---

private const val DEFAULT_SHAPE_COLOR = 0xFF5BC0F8L
private const val LINE_WIDTH = 8f
private const val LINE_HEAD = 44f

private val SHAPE_PALETTE = listOf(
    0xFF5BC0F8L, 0xFFFF6B6BL, 0xFFFFA94DL, 0xFFFFE066L, 0xFF69DB7CL,
    0xFFB197FCL, 0xFFF783ACL, 0xFFADB5BDL, 0xFFF8F9FAL, 0xFF212529L
)

private val CORNERS = listOf(Offset(-1f, -1f), Offset(1f, -1f), Offset(1f, 1f), Offset(-1f, 1f))

enum class ShapeCategory(val label: String) {
    BASIC("Basic"),
    GEOMETRY("Geometry"),
    SYMBOLS("Symbols")
}

/** [aspect] is width / height for a newly inserted shape. Lines are stored as a horizontal segment (height 0) plus rotation. */
enum class ShapeType(
    val label: String,
    val category: ShapeCategory,
    val aspect: Float = 1f,
    val isLine: Boolean = false
) {
    LINE("Line", ShapeCategory.BASIC, isLine = true),
    ARROW_LINE("Arrow line", ShapeCategory.BASIC, isLine = true),
    RECTANGLE("Square", ShapeCategory.BASIC),
    ROUNDED_RECTANGLE("Rounded square", ShapeCategory.BASIC),
    ELLIPSE("Circle", ShapeCategory.BASIC),
    TRIANGLE("Triangle", ShapeCategory.BASIC),
    RIGHT_TRIANGLE("Right triangle", ShapeCategory.BASIC),
    ARROW("Arrow", ShapeCategory.BASIC, aspect = 1.5f),
    DOUBLE_ARROW("Double arrow", ShapeCategory.BASIC, aspect = 1.6f),
    DIAMOND("Diamond", ShapeCategory.BASIC),
    SPEECH_BUBBLE("Speech bubble", ShapeCategory.BASIC, aspect = 1.25f),
    CALLOUT("Callout", ShapeCategory.BASIC, aspect = 1.1f),
    PENTAGON("Pentagon", ShapeCategory.BASIC),
    STAR("Star", ShapeCategory.BASIC),

    HEXAGON("Hexagon", ShapeCategory.GEOMETRY, aspect = 1.15f),
    OCTAGON("Octagon", ShapeCategory.GEOMETRY),
    PARALLELOGRAM("Parallelogram", ShapeCategory.GEOMETRY, aspect = 1.4f),
    TRAPEZOID("Trapezoid", ShapeCategory.GEOMETRY, aspect = 1.3f),
    CROSS("Cross", ShapeCategory.GEOMETRY),

    HEART("Heart", ShapeCategory.SYMBOLS),
    LIGHTNING("Lightning bolt", ShapeCategory.SYMBOLS, aspect = 0.8f),
    CLOUD("Cloud", ShapeCategory.SYMBOLS, aspect = 1.4f),
    HEXAGRAM("Six-point star", ShapeCategory.SYMBOLS),
    BURST("Burst", ShapeCategory.SYMBOLS)
}

/**
 * [x]/[y] is the top-left corner of the un-rotated box in world coordinates; [rotation] is in degrees,
 * around the box centre. Immutable on purpose: update with copy() through the SnapshotStateList.
 */
data class BoardShape(
    val id: String = UUID.randomUUID().toString(),
    val type: ShapeType = ShapeType.RECTANGLE,
    val x: Float = 0f,
    val y: Float = 0f,
    val width: Float = 200f,
    val height: Float = 200f,
    val rotation: Float = 0f,
    val colorValue: Long = DEFAULT_SHAPE_COLOR
)

// ---------- geometry helpers ----------

private fun rotateVec(v: Offset, degrees: Float): Offset {
    val r = Math.toRadians(degrees.toDouble())
    val c = cos(r).toFloat()
    val s = sin(r).toFloat()
    return Offset(v.x * c - v.y * s, v.x * s + v.y * c)
}

private fun BoardShape.center() = Offset(x + width / 2f, y + height / 2f)
private fun BoardShape.localToWorld(local: Offset): Offset = center() + rotateVec(local, rotation)
private fun BoardShape.worldToLocal(world: Offset): Offset = rotateVec(world - center(), -rotation)

private fun BoardShape.lineEndpoints(): Pair<Offset, Offset> {
    val half = rotateVec(Offset(width / 2f, 0f), rotation)
    val c = center()
    return (c - half) to (c + half)
}

private fun BoardShape.outlinePoints(): List<Offset> =
    if (type.isLine) lineEndpoints().toList()
    else CORNERS.map { localToWorld(Offset(it.x * width / 2f, it.y * height / 2f)) }

private fun distanceToSegment(p: Offset, a: Offset, b: Offset): Float {
    val ab = b - a
    val len2 = ab.x * ab.x + ab.y * ab.y
    if (len2 == 0f) return (p - a).getDistance()
    val t = (((p.x - a.x) * ab.x + (p.y - a.y) * ab.y) / len2).coerceIn(0f, 1f)
    return (p - (a + ab * t)).getDistance()
}

private fun BoardShape.hitTest(world: Offset, lineTolerance: Float): Boolean {
    if (type.isLine) {
        val (p1, p2) = lineEndpoints()
        return distanceToSegment(world, p1, p2) <= lineTolerance
    }
    val q = worldToLocal(world)
    val hw = width / 2f
    val hh = height / 2f
    return when (type) {
        ShapeType.ELLIPSE -> (q.x / hw) * (q.x / hw) + (q.y / hh) * (q.y / hh) <= 1.05f
        ShapeType.DIAMOND -> abs(q.x) / hw + abs(q.y) / hh <= 1.05f
        else -> abs(q.x) <= hw && abs(q.y) <= hh
    }
}

// ---------- drag / resize / rotate state machine ----------

private enum class DragMode { MOVE, RESIZE, ROTATE, ENDPOINT }

/**
 * Snapshot taken when a touch lands on a shape. Every drag step is computed from [start] and
 * [startPointer], so errors never accumulate.
 */
private class ShapeDragState(
    val mode: DragMode,
    val start: BoardShape,
    val startPointer: Offset,
    val corner: Offset = Offset.Zero,
    val grab: Offset = Offset.Zero
)

private fun beginShapeDrag(
    shapes: List<BoardShape>,
    selectedId: String?,
    world: Offset,
    zoom: Float,
    density: Density
): ShapeDragState? {
    val handleRadius = with(density) { 24.dp.toPx() } / zoom
    val rotateOffset = with(density) { 32.dp.toPx() } / zoom
    val lineTolerance = with(density) { 16.dp.toPx() } / zoom

    // 1. Handles of the currently selected shape win over everything else.
    val selected = shapes.firstOrNull { it.id == selectedId }
    if (selected != null) {
        if (selected.type.isLine) {
            val (p1, p2) = selected.lineEndpoints()
            if ((world - p1).getDistance() <= handleRadius) {
                return ShapeDragState(DragMode.ENDPOINT, selected, world, Offset(-1f, 0f), world - p1)
            }
            if ((world - p2).getDistance() <= handleRadius) {
                return ShapeDragState(DragMode.ENDPOINT, selected, world, Offset(1f, 0f), world - p2)
            }
        } else {
            val rotateHandle = selected.localToWorld(Offset(0f, -selected.height / 2f - rotateOffset))
            if ((world - rotateHandle).getDistance() <= handleRadius) {
                return ShapeDragState(DragMode.ROTATE, selected, world)
            }
            for (corner in CORNERS) {
                val pos = selected.localToWorld(Offset(corner.x * selected.width / 2f, corner.y * selected.height / 2f))
                if ((world - pos).getDistance() <= handleRadius) {
                    return ShapeDragState(DragMode.RESIZE, selected, world, corner, world - pos)
                }
            }
        }
    }

    // 2. Otherwise grab the top-most shape under the finger.
    val hit = shapes.lastOrNull { it.hitTest(world, lineTolerance) } ?: return null
    return ShapeDragState(DragMode.MOVE, hit, world)
}

private fun applyShapeDrag(d: ShapeDragState, pointer: Offset, minSize: Float): BoardShape {
    val s = d.start
    return when (d.mode) {
        DragMode.MOVE -> s.copy(
            x = s.x + (pointer.x - d.startPointer.x),
            y = s.y + (pointer.y - d.startPointer.y)
        )
        DragMode.ROTATE -> {
            val c = s.center()
            val raw = Math.toDegrees(
                atan2((pointer.y - c.y).toDouble(), (pointer.x - c.x).toDouble())
            ).toFloat() + 90f // the handle sits above the shape, i.e. at -90 degrees
            s.copy(rotation = snapAngle(raw))
        }
        DragMode.RESIZE -> resizeShape(s, pointer - d.grab, d.corner, minSize)
        DragMode.ENDPOINT -> moveLineEndpoint(s, pointer - d.grab, movingSecond = d.corner.x > 0f, minLength = minSize)
    }
}

/** Snaps to multiples of 45 degrees when within 4 degrees of one. */
private fun snapAngle(deg: Float): Float {
    val normalized = ((deg % 360f) + 360f) % 360f
    val nearest = (normalized / 45f).roundToInt() * 45f
    return if (abs(normalized - nearest) <= 4f) nearest % 360f else normalized
}

/** Drags one corner while the opposite corner stays pinned in world space (works for rotated shapes too). */
private fun resizeShape(start: BoardShape, pointer: Offset, corner: Offset, minSize: Float): BoardShape {
    val c = start.center()
    val q = rotateVec(pointer - c, -start.rotation)          // dragged corner, in the old un-rotated frame
    val anchor = Offset(-corner.x * start.width / 2f, -corner.y * start.height / 2f)
    val newW = max(minSize, (q.x - anchor.x) * corner.x)
    val newH = max(minSize, (q.y - anchor.y) * corner.y)
    val newCenterLocal = Offset(anchor.x + corner.x * newW / 2f, anchor.y + corner.y * newH / 2f)
    val newCenter = c + rotateVec(newCenterLocal, start.rotation)
    return start.copy(
        x = newCenter.x - newW / 2f,
        y = newCenter.y - newH / 2f,
        width = newW,
        height = newH
    )
}

/** Lines: dragging an endpoint moves just that end; length and angle follow. */
private fun moveLineEndpoint(start: BoardShape, point: Offset, movingSecond: Boolean, minLength: Float): BoardShape {
    val (p1, p2) = start.lineEndpoints()
    val a = if (movingSecond) p1 else point
    val b = if (movingSecond) point else p2
    val mid = (a + b) / 2f
    val length = max(minLength, (b - a).getDistance())
    val angle = Math.toDegrees(atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())).toFloat()
    return start.copy(x = mid.x - length / 2f, y = mid.y, width = length, height = 0f, rotation = angle)
}

// ---------- drawing ----------

private fun DrawScope.drawLineShape(
    color: Color,
    from: Offset,
    to: Offset,
    width: Float,
    arrowHead: Boolean,
    headLength: Float
) {
    drawLine(color, from, to, strokeWidth = width, cap = StrokeCap.Round)
    if (arrowHead) {
        val angle = atan2(to.y - from.y, to.x - from.x)
        val back = angle + PI.toFloat()
        val spread = 0.5f
        listOf(back - spread, back + spread).forEach { a ->
            drawLine(
                color,
                to,
                Offset(to.x + headLength * cos(a), to.y + headLength * sin(a)),
                strokeWidth = width,
                cap = StrokeCap.Round
            )
        }
    }
}

private fun DrawScope.drawBoardShape(shape: BoardShape) {
    val color = Color(shape.colorValue)
    val pivot = Offset(shape.x + shape.width / 2f, shape.y + shape.height / 2f)
    withTransform({
        rotate(degrees = shape.rotation, pivot = pivot)
        translate(left = shape.x, top = shape.y)
    }) {
        if (shape.type.isLine) {
            drawLineShape(
                color,
                Offset.Zero,
                Offset(shape.width, 0f),
                LINE_WIDTH,
                shape.type == ShapeType.ARROW_LINE,
                LINE_HEAD
            )
        } else {
            drawPath(shape.type.buildPath(shape.width, shape.height), color)
        }
    }
}

/** Blue outline, four corner handles and a rotation handle. Everything is kept a constant size on screen. */
private fun DrawScope.drawSelectionOverlay(shape: BoardShape, zoom: Float) {
    val blue = Color(0xFF2196F3)
    val inv = 1f / zoom
    val lineWidth = 1.5.dp.toPx() * inv
    val handleRadius = 6.dp.toPx() * inv
    val rotateOffset = 32.dp.toPx() * inv

    if (shape.type.isLine) {
        val (p1, p2) = shape.lineEndpoints()
        drawHandle(p1, handleRadius, blue)
        drawHandle(p2, handleRadius, blue)
        return
    }

    val corners = shape.outlinePoints()
    val outline = Path().apply {
        moveTo(corners[0].x, corners[0].y)
        for (i in 1 until corners.size) lineTo(corners[i].x, corners[i].y)
        close()
    }
    drawPath(outline, blue, style = Stroke(width = lineWidth))

    val topMiddle = shape.localToWorld(Offset(0f, -shape.height / 2f))
    val rotateHandle = shape.localToWorld(Offset(0f, -shape.height / 2f - rotateOffset))
    drawLine(blue, topMiddle, rotateHandle, strokeWidth = lineWidth)
    drawHandle(rotateHandle, handleRadius * 1.1f, blue)
    corners.forEach { drawHandle(it, handleRadius, blue) }
}

private fun DrawScope.drawHandle(center: Offset, radius: Float, color: Color) {
    drawCircle(Color.White, radius = radius * 1.4f, center = center)
    drawCircle(color, radius = radius, center = center)
}

// ---------- shape outlines ----------

private fun Path.poly(w: Float, h: Float, vararg p: Float) {
    moveTo(p[0] * w, p[1] * h)
    var i = 2
    while (i < p.size) {
        lineTo(p[i] * w, p[i + 1] * h)
        i += 2
    }
    close()
}

private fun polygonPoints(sides: Int, startDegrees: Double): List<Offset> = List(sides) { i ->
    val a = Math.toRadians(startDegrees + 360.0 * i / sides)
    Offset(cos(a).toFloat(), sin(a).toFloat())
}

private fun starPoints(points: Int, innerRatio: Float): List<Offset> = List(points * 2) { i ->
    val r = if (i % 2 == 0) 1f else innerRatio
    val a = Math.toRadians(-90.0 + 360.0 * i / (points * 2))
    Offset((cos(a) * r).toFloat(), (sin(a) * r).toFloat())
}

/** Stretches the points so the polygon exactly fills the w x h box. */
private fun Path.fittedPolygon(w: Float, h: Float, pts: List<Offset>) {
    val minX = pts.minOf { it.x }
    val maxX = pts.maxOf { it.x }
    val minY = pts.minOf { it.y }
    val maxY = pts.maxOf { it.y }
    val sx = if (maxX > minX) w / (maxX - minX) else 1f
    val sy = if (maxY > minY) h / (maxY - minY) else 1f
    pts.forEachIndexed { i, p ->
        val px = (p.x - minX) * sx
        val py = (p.y - minY) * sy
        if (i == 0) moveTo(px, py) else lineTo(px, py)
    }
    close()
}

private fun ShapeType.buildPath(w: Float, h: Float): Path = Path().apply {
    when (this@buildPath) {
        ShapeType.LINE, ShapeType.ARROW_LINE -> Unit
        ShapeType.RECTANGLE -> addRect(Rect(0f, 0f, w, h))
        ShapeType.ROUNDED_RECTANGLE -> {
            val r = min(w, h) * 0.2f
            addRoundRect(RoundRect(0f, 0f, w, h, CornerRadius(r, r)))
        }
        ShapeType.ELLIPSE -> addOval(Rect(0f, 0f, w, h))
        ShapeType.TRIANGLE -> poly(w, h, 0.5f, 0f, 1f, 1f, 0f, 1f)
        ShapeType.RIGHT_TRIANGLE -> poly(w, h, 0f, 0f, 1f, 1f, 0f, 1f)
        ShapeType.ARROW -> poly(w, h, 0f, 0.28f, 0.55f, 0.28f, 0.55f, 0f, 1f, 0.5f, 0.55f, 1f, 0.55f, 0.72f, 0f, 0.72f)
        ShapeType.DOUBLE_ARROW -> poly(
            w, h,
            0f, 0.5f, 0.26f, 0f, 0.26f, 0.28f, 0.74f, 0.28f, 0.74f, 0f,
            1f, 0.5f, 0.74f, 1f, 0.74f, 0.72f, 0.26f, 0.72f, 0.26f, 1f
        )
        ShapeType.DIAMOND -> poly(w, h, 0.5f, 0f, 1f, 0.5f, 0.5f, 1f, 0f, 0.5f)
        ShapeType.SPEECH_BUBBLE -> {
            val body = Path().apply { addOval(Rect(0f, 0f, w, h * 0.8f)) }
            val tail = Path().apply { poly(w, h, 0.22f, 0.68f, 0.12f, 1f, 0.45f, 0.78f) }
            addPath(Path.combine(PathOperation.Union, body, tail))
        }
        ShapeType.CALLOUT -> {
            val r = min(w, h) * 0.18f
            val body = Path().apply { addRoundRect(RoundRect(0.14f * w, 0f, w, h, CornerRadius(r, r))) }
            val tail = Path().apply { poly(w, h, 0.2f, 0.42f, 0f, 0.6f, 0.2f, 0.76f) }
            addPath(Path.combine(PathOperation.Union, body, tail))
        }
        ShapeType.PENTAGON -> fittedPolygon(w, h, polygonPoints(5, -90.0))
        ShapeType.STAR -> fittedPolygon(w, h, starPoints(5, 0.4f))
        ShapeType.HEXAGON -> fittedPolygon(w, h, polygonPoints(6, 0.0))
        ShapeType.OCTAGON -> fittedPolygon(w, h, polygonPoints(8, 22.5))
        ShapeType.PARALLELOGRAM -> poly(w, h, 0.22f, 0f, 1f, 0f, 0.78f, 1f, 0f, 1f)
        ShapeType.TRAPEZOID -> poly(w, h, 0.2f, 0f, 0.8f, 0f, 1f, 1f, 0f, 1f)
        ShapeType.CROSS -> poly(
            w, h,
            0.33f, 0f, 0.67f, 0f, 0.67f, 0.33f, 1f, 0.33f, 1f, 0.67f, 0.67f, 0.67f,
            0.67f, 1f, 0.33f, 1f, 0.33f, 0.67f, 0f, 0.67f, 0f, 0.33f, 0.33f, 0.33f
        )
        ShapeType.HEART -> {
            // Material "favorite" outline, normalised from its 24x24 grid (x 2..22, y 3..21.35).
            fun px(x: Float) = (x - 2f) / 20f * w
            fun py(y: Float) = (y - 3f) / 18.35f * h
            moveTo(px(12f), py(21.35f))
            lineTo(px(10.55f), py(20.03f))
            cubicTo(px(5.4f), py(15.36f), px(2f), py(12.28f), px(2f), py(8.5f))
            cubicTo(px(2f), py(5.42f), px(4.42f), py(3f), px(7.5f), py(3f))
            cubicTo(px(9.24f), py(3f), px(10.91f), py(3.81f), px(12f), py(5.09f))
            cubicTo(px(13.09f), py(3.81f), px(14.76f), py(3f), px(16.5f), py(3f))
            cubicTo(px(19.58f), py(3f), px(22f), py(5.42f), px(22f), py(8.5f))
            cubicTo(px(22f), py(12.28f), px(18.6f), py(15.36f), px(13.45f), py(20.04f))
            lineTo(px(12f), py(21.35f))
            close()
        }
        ShapeType.LIGHTNING -> poly(w, h, 0.6f, 0f, 0.2f, 0.55f, 0.48f, 0.55f, 0.36f, 1f, 0.82f, 0.4f, 0.54f, 0.4f, 0.75f, 0f)
        ShapeType.CLOUD -> {
            var cloud = Path().apply {
                addRoundRect(RoundRect(0.04f * w, 0.45f * h, 0.96f * w, 0.95f * h, CornerRadius(0.25f * h, 0.25f * h)))
            }
            listOf(
                Rect(0.10f * w, 0.28f * h, 0.50f * w, 0.75f * h),
                Rect(0.28f * w, 0.05f * h, 0.72f * w, 0.65f * h),
                Rect(0.52f * w, 0.25f * h, 0.92f * w, 0.75f * h)
            ).forEach { bump ->
                cloud = Path.combine(PathOperation.Union, cloud, Path().apply { addOval(bump) })
            }
            addPath(cloud)
        }
        ShapeType.HEXAGRAM -> fittedPolygon(w, h, starPoints(6, 0.577f))
        ShapeType.BURST -> fittedPolygon(w, h, starPoints(12, 0.78f))
    }
}

// ---------- picker popup ----------

@Composable
fun ShapePickerPopup(onPick: (ShapeType) -> Unit, modifier: Modifier = Modifier) {
    var category by remember { mutableStateOf(ShapeCategory.BASIC) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    val items = if (searching && query.isNotBlank()) {
        ShapeType.entries.filter { it.label.contains(query.trim(), ignoreCase = true) }
    } else {
        ShapeType.entries.filter { it.category == category }
    }

    Surface(
        modifier = modifier
            .width(320.dp)
            .shadow(16.dp, RoundedCornerShape(28.dp)),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header Row / Search Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (searching) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search shapes...", style = MaterialTheme.typography.bodyMedium) },
                        singleLine = true,
                        shape = CircleShape,
                        leadingIcon = {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        trailingIcon = {
                            IconButton(onClick = {
                                query = ""
                                searching = false
                            }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Clear search",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color.Transparent
                        ),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 44.dp)
                    )
                } else {
                    Text(
                        text = "Shapes",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                    IconButton(
                        onClick = { searching = true },
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = "Search shapes",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // Categories Filter Chips
            if (!searching) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ShapeCategory.entries.forEach { cat ->
                        val selected = cat == category
                        FilterChip(
                            selected = selected,
                            onClick = { category = cat },
                            label = { Text(cat.label) },
                            shape = CircleShape,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            border = null
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Shapes Grid
            if (items.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No shapes found",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.height(260.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(items) { type ->
                        ShapePickerCell(type) { onPick(type) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShapePickerCell(type: ShapeType, onClick: () -> Unit) {
    val primaryColor = MaterialTheme.colorScheme.primary

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .aspectRatio(1f)
            .semantics { contentDescription = type.label }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawShapePreview(type, primaryColor)
            }
        }
    }
}

private fun DrawScope.drawShapePreview(type: ShapeType, previewColor: Color = Color(DEFAULT_SHAPE_COLOR)) {
    if (type.isLine) {
        drawLineShape(
            previewColor,
            Offset(size.width * 0.08f, size.height * 0.92f),
            Offset(size.width * 0.92f, size.height * 0.08f),
            2.5.dp.toPx(),
            type == ShapeType.ARROW_LINE,
            14.dp.toPx()
        )
        return
    }
    val w: Float
    val h: Float
    if (type.aspect >= 1f) {
        w = size.width
        h = size.width / type.aspect
    } else {
        h = size.height
        w = size.height * type.aspect
    }
    translate(left = (size.width - w) / 2f, top = (size.height - h) / 2f) {
        drawPath(type.buildPath(w, h), previewColor)
    }
}

// ---------- floating action bar for the selected shape ----------

@Composable
private fun ShapeContextBar(
    shapeId: String,
    centerX: Float,
    topY: Float,
    bottomY: Float,
    gapPx: Float,
    minTopPx: Float,
    currentColor: Long,
    palette: List<Long> = SHAPE_PALETTE,
    onColor: (Long) -> Unit,
    onDuplicate: (() -> Unit)?,
    onBringToFront: (() -> Unit)?,
    onDelete: () -> Unit
) {
    var showColors by remember(shapeId) { mutableStateOf(false) }

    Surface(
        modifier = Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
            layout(constraints.maxWidth, constraints.maxHeight) {
                val x = (centerX - placeable.width / 2f).roundToInt()
                    .coerceIn(0, (constraints.maxWidth - placeable.width).coerceAtLeast(0))
                var y = (topY - gapPx - placeable.height).roundToInt()
                // Not enough room above (under the title bar)? Put it under the shape instead.
                if (y < minTopPx) y = (bottomY + gapPx / 2f).roundToInt()
                y = y.coerceIn(0, (constraints.maxHeight - placeable.height).coerceAtLeast(0))
                placeable.place(x, y)
            }
        },
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showColors) {
                palette.forEach { c ->
                    Box(
                        modifier = Modifier
                            .padding(4.dp)
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color(c))
                            .then(if (c == currentColor) Modifier.border(2.dp, Color.White, CircleShape) else Modifier)
                            .clickable {
                                onColor(c)
                                showColors = false
                            }
                    )
                }
                IconButton(onClick = { showColors = false }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close colors")
                }
            } else {
                IconButton(onClick = { showColors = true }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Palette, contentDescription = "Color")
                }
                if (onDuplicate != null) {
                    IconButton(onClick = onDuplicate, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Duplicate")
                    }
                }
                if (onBringToFront != null) {
                    IconButton(onClick = onBringToFront, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.FlipToFront, contentDescription = "Bring to front")
                    }
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

// --- 4. PEN COLOURS, ERASER, STROKE SELECTION ---

private val PEN_PALETTE = listOf(
    0xFFFFFFFFL, 0xFF5BC0F8L, 0xFFFF6B6BL, 0xFFFFA94DL,
    0xFFFFE066L, 0xFF69DB7CL, 0xFFB197FCL, 0xFFF783ACL
)

/** Eraser glyph (Material "ink eraser" outline), built by hand because the icon set may not ship it. */
private val EraserIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Eraser",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).addPath(
        pathData = PathParser().parsePathString(
            "M16.24,3.56l4.95,4.94c0.78,0.79 0.78,2.05 0,2.84L12,20.53a4.008,4.008 0,0 1,-5.66,0L2.81,17c-0.78,-0.79 -0.78,-2.05 0,-2.84l10.6,-10.6c0.79,-0.78 2.05,-0.78 2.83,0zM4.22,15.58l3.54,3.53c0.78,0.79 2.04,0.79 2.83,0l3.53,-3.53 -4.95,-4.95 -4.95,4.95z"
        ).toNodes(),
        fill = SolidColor(Color.Black)
    ).build()
}

/** True when [world] is within [tolerance] (plus half the pen width) of any segment of the stroke. */
private fun CanvasStroke.hitTest(world: Offset, tolerance: Float): Boolean {
    val pts = points
    if (pts.isEmpty()) return false
    val reach = tolerance + baseWidth / 2f
    if (pts.size == 1) return (pts[0].position - world).getDistance() <= reach
    for (i in 1 until pts.size) {
        if (distanceToSegment(world, pts[i - 1].position, pts[i].position) <= reach) return true
    }
    return false
}

@Composable
private fun PenColorPopup(selected: Color, onPick: (Color) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.shadow(12.dp, RoundedCornerShape(24.dp)),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PEN_PALETTE.forEach { c ->
                val color = Color(c)
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(color)
                        .then(
                            if (color == selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                            else Modifier.border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                        )
                        .clickable { onPick(color) }
                        .semantics { contentDescription = "Pen colour" }
                )
            }
        }
    }
}

// --- 5. STICKY NOTE SELECTION / MOVE / RESIZE ---

private val NOTE_PALETTE = listOf(
    0xFFFFEE58L, 0xFFFFB74DL, 0xFFF48FB1L, 0xFF81D4FAL,
    0xFFA5D6A7L, 0xFFCE93D8L, 0xFFFFFFFFL, 0xFFB0BEC5L
)

private class NoteDragState(
    val resize: Boolean,
    val start: StickyNote,
    val startPointer: Offset,
    val corner: Offset = Offset.Zero,
    val grab: Offset = Offset.Zero
)

/** Notes are sized in dp inside a layer that is scaled by the zoom, so in world units they are dp * density. */
private fun noteSizePx(n: StickyNote, density: Density): Offset =
    Offset(with(density) { n.width.dp.toPx() }, with(density) { n.height.dp.toPx() })

private fun beginNoteDrag(
    notes: List<StickyNote>,
    selectedId: String?,
    world: Offset,
    zoom: Float,
    density: Density
): NoteDragState? {
    val handleRadius = with(density) { 20.dp.toPx() } / zoom

    // Corner handles of the selected note come first.
    val selected = notes.firstOrNull { it.id == selectedId }
    if (selected != null) {
        val size = noteSizePx(selected, density)
        for (corner in CORNERS) {
            val pos = Offset(
                selected.x + if (corner.x > 0f) size.x else 0f,
                selected.y + if (corner.y > 0f) size.y else 0f
            )
            if ((world - pos).getDistance() <= handleRadius) {
                return NoteDragState(true, selected, world, corner, world - pos)
            }
        }
    }

    // Otherwise the top-most note under the finger.
    val hit = notes.lastOrNull { n ->
        val size = noteSizePx(n, density)
        world.x in n.x..(n.x + size.x) && world.y in n.y..(n.y + size.y)
    } ?: return null
    return NoteDragState(false, hit, world)
}

private fun applyNoteDrag(d: NoteDragState, pointer: Offset, density: Density): StickyNote {
    val s = d.start
    if (!d.resize) {
        return s.copy(x = s.x + (pointer.x - d.startPointer.x), y = s.y + (pointer.y - d.startPointer.y))
    }
    // Dragged corner follows the finger; the opposite corner stays where it was.
    val size = noteSizePx(s, density)
    val p = pointer - d.grab
    val minPx = with(density) { 80.dp.toPx() }
    val anchorX = if (d.corner.x > 0f) s.x else s.x + size.x
    val anchorY = if (d.corner.y > 0f) s.y else s.y + size.y
    val newW = max(minPx, (p.x - anchorX) * d.corner.x)
    val newH = max(minPx, (p.y - anchorY) * d.corner.y)
    return s.copy(
        x = if (d.corner.x > 0f) anchorX else anchorX - newW,
        y = if (d.corner.y > 0f) anchorY else anchorY - newH,
        width = with(density) { newW.toDp().value },
        height = with(density) { newH.toDp().value }
    )
}
