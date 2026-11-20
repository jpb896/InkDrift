package com.jpb.inkdrift

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.hypot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                    FreeformAppRoot()
                }
            }
        }
    }
}

// --- APP NAVIGATION ROUTING ---

sealed class AppScreen {
    data object Dashboard : AppScreen()
    data class Studio(val boardId: String, val boardTitle: String) : AppScreen()
}

@Composable
fun FreeformAppRoot() {
    var currentScreen by remember { mutableStateOf<AppScreen>(AppScreen.Dashboard) }

    when (val screen = currentScreen) {
        is AppScreen.Dashboard -> {
            FreeformMainDashboardScreen(
                onBoardClick = { boardId, title ->
                    currentScreen = AppScreen.Studio(boardId, title)
                }
            )
        }
        is AppScreen.Studio -> {
            FreeformStudioCanvasScreen(
                boardTitle = screen.boardTitle,
                onBack = { currentScreen = AppScreen.Dashboard }
            )
        }
    }
}


// --- 1. DASHBOARD SCREEN & MODELS ---

data class FreeformBoard(
    val id: String,
    val title: String,
    val subtitle: String
)

enum class FreeformCategory(val label: String, val icon: ImageVector, val count: Int) {
    ALL("All Boards", Icons.Default.Dashboard, 1),
    RECENTS("Recents", Icons.Default.Schedule, 1),
    SHARED("Shared", Icons.Default.People, 0),
    FAVORITES("Favorites", Icons.Default.Favorite, 0),
    DELETED("Recently Deleted", Icons.Default.Delete, 1)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeformMainDashboardScreen(onBoardClick: (String, String) -> Unit) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var selectedCategory by remember { mutableStateOf(FreeformCategory.ALL) }
    var searchQuery by remember { mutableStateOf("") }

    val boards = remember {
        mutableStateListOf(
            FreeformBoard(id = "1", title = "Untitled", subtitle = "Today, 5:22 PM")
        )
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
                        text = "Freeform",
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 24.dp)
                    )

                    FreeformCategory.entries.forEach { category ->
                        NavigationDrawerItem(
                            icon = { Icon(category.icon, contentDescription = null) },
                            label = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(category.label)
                                    Text(category.count.toString(), style = MaterialTheme.typography.bodyMedium)
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
                        boards.add(FreeformBoard(id = newId, title = "Untitled Board", subtitle = "Just now"))
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

                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(boards) { board ->
                        DashboardBoardCard(board = board, onClick = { onBoardClick(board.id, board.title) })
                    }
                }
            }
        }
    }
}

@Composable
fun DashboardBoardCard(board: FreeformBoard, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().height(220.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                Text(text = "hello world!\nXDA", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Text(text = board.title, style = MaterialTheme.typography.titleSmall)
                Text(text = board.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}


// --- 2. INFINITE CANVAS STUDIO SCREEN & DRAWING ENGINE ---

enum class StudioTool { PAN, PEN, NOTE, SHAPE }

data class CanvasStrokePoint(val position: Offset, val pressure: Float)
data class CanvasStroke(val id: String = UUID.randomUUID().toString(), val points: List<CanvasStrokePoint>, val color: Color, val baseWidth: Float)

@Composable
fun FreeformStudioCanvasScreen(boardTitle: String, onBack: () -> Unit) {
    var selectedTool by remember { mutableStateOf(StudioTool.PEN) }
    val activeColor by remember { mutableStateOf(Color(0xFFFFFFFF)) }
    val baseStrokeWidth by remember { mutableFloatStateOf(12f) }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF181818))) {

        FreeformInteractiveCanvas(
            isPenActive = (selectedTool == StudioTool.PEN),
            activeColor = activeColor,
            baseStrokeWidth = baseStrokeWidth
        )

        Surface(
            modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
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

        Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)) {
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
                    StudioToolButton(Icons.AutoMirrored.Filled.StickyNote2, "Note", selectedTool == StudioTool.NOTE) { selectedTool = StudioTool.NOTE }
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
fun FreeformInteractiveCanvas(isPenActive: Boolean, activeColor: Color, baseStrokeWidth: Float) {
    var scale by remember { mutableFloatStateOf(1f) }
    var canvasOffset by remember { mutableStateOf(Offset.Zero) }

    val strokes = remember { mutableStateListOf<CanvasStroke>() }
    val currentPoints = remember { mutableStateListOf<CanvasStrokePoint>() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(isPenActive, activeColor, baseStrokeWidth) {
                detectCanvasGestures(
                    isPenActive = isPenActive,
                    getCanvasOffset = { canvasOffset },
                    getScale = { scale },
                    onTransform = { pan, zoom, centroid ->
                        val oldScale = scale
                        val newScale = (scale * zoom).coerceIn(0.15f, 6.0f)
                        val scaleFactor = newScale / oldScale
                        canvasOffset = (canvasOffset - centroid) * scaleFactor + centroid + pan
                        scale = newScale
                    },
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
                        }
                    }
                )
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
        val nextMid = if (i == points.size - 1) current.position else lerp(points[i - 1].position, current.position, 0.5f)
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