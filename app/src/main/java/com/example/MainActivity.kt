package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.FolderCopy
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.PoRepository
import com.example.data.authStateFlow
import com.example.data.signOutUser
import com.example.model.AppDestination
import com.example.ui.auth.AuthScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.ExcelExportScreen
import com.example.ui.screens.PdfPrintScreen
import com.example.ui.screens.PoBreakdownScreen
import com.example.ui.screens.PoTransferScreen
import com.example.ui.screens.SavedPosScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.UploadPoScreen
import com.example.ui.theme.LslForestGreen
import com.example.ui.theme.LslForestGreenDark
import com.example.ui.theme.LslMintBorder
import com.example.ui.theme.LslMintTint
import com.example.ui.theme.LslPoManagerTheme
import com.example.ui.theme.LslPureWhite
import com.example.ui.theme.MonoNumericStyle
import com.example.viewmodel.LslPoViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LslPoManagerTheme {
                AppNavigation()
            }
        }
    }
}

@Composable
fun AppNavigation(auth: FirebaseAuth = Firebase.auth) {
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)
    val user = currentUser

    if (user == null) {
        AuthScreen(onAuthSuccess = {})
    } else {
        key(user.uid) {
            LslAuthenticatedWorkspace(user = user)
        }
    }
}

private fun destinationIcon(dest: AppDestination): ImageVector = when (dest) {
    AppDestination.DASHBOARD -> Icons.Outlined.Dashboard
    AppDestination.UPLOAD_PO -> Icons.Outlined.UploadFile
    AppDestination.PO_TRANSFER -> Icons.Outlined.SwapHoriz
    AppDestination.PO_BREAKDOWN -> Icons.Outlined.TableChart
    AppDestination.SAVED_POS -> Icons.Outlined.FolderCopy
    AppDestination.EXCEL_EXPORT -> Icons.Outlined.GridOn
    AppDestination.PDF_PRINT -> Icons.Outlined.Print
    AppDestination.SETTINGS -> Icons.Outlined.Settings
}

private val bottomBarDestinations = listOf(
    AppDestination.DASHBOARD,
    AppDestination.UPLOAD_PO,
    AppDestination.PO_TRANSFER,
    AppDestination.PO_BREAKDOWN,
    AppDestination.SAVED_POS
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LslAuthenticatedWorkspace(user: FirebaseUser) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val viewModel: LslPoViewModel = viewModel(
        key = user.uid,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                val databaseId = app.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(databaseId)
                LslPoViewModel(
                    repository = PoRepository(db),
                    currentUserId = user.uid,
                    userDisplayName = user.displayName ?: user.email ?: "LSL Merchandiser"
                )
            }
        }
    )

    val currentDestination by viewModel.currentDestination.collectAsStateWithLifecycle()
    val canNavigateBack by viewModel.canNavigateBack.collectAsStateWithLifecycle()
    val savedPosState by viewModel.savedPosState.collectAsStateWithLifecycle()
    val settings by viewModel.settingsState.collectAsStateWithLifecycle()
    val uploadState by viewModel.uploadState.collectAsStateWithLifecycle()
    val activePoState by viewModel.activePoState.collectAsStateWithLifecycle()
    val activeAuditLogs by viewModel.activeAuditLogs.collectAsStateWithLifecycle()
    val savedOriginalSource by viewModel.savedOriginalSource.collectAsStateWithLifecycle()
    val bannerMessage by viewModel.bannerMessage.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    BackHandler(enabled = canNavigateBack) {
        viewModel.navigateBack()
    }

    LaunchedEffect(bannerMessage) {
        val msg = bannerMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearBanner()
        }
    }

    // Laptop & Desktop Hardware Keyboard Shortcuts (Ctrl+U, Ctrl+S, Ctrl+E, Ctrl+P, Ctrl+1..8)
    val keyboardShortcutModifier = Modifier.onPreviewKeyEvent { keyEvent ->
        if (keyEvent.type == KeyEventType.KeyDown && keyEvent.isCtrlPressed) {
            when (keyEvent.key) {
                Key.U -> { viewModel.navigateTo(AppDestination.UPLOAD_PO); true }
                Key.S -> {
                    if (activePoState.po != null) {
                        viewModel.requestSaveActivePo(markVerified = false, forceDuplicateSave = false)
                        true
                    } else false
                }
                Key.E -> { viewModel.navigateTo(AppDestination.EXCEL_EXPORT); true }
                Key.P -> { viewModel.navigateTo(AppDestination.PDF_PRINT); true }
                Key.One -> { viewModel.navigateTo(AppDestination.DASHBOARD); true }
                Key.Two -> { viewModel.navigateTo(AppDestination.UPLOAD_PO); true }
                Key.Three -> { viewModel.navigateTo(AppDestination.PO_TRANSFER); true }
                Key.Four -> { viewModel.navigateTo(AppDestination.PO_BREAKDOWN); true }
                Key.Five -> { viewModel.navigateTo(AppDestination.SAVED_POS); true }
                Key.Six -> { viewModel.navigateTo(AppDestination.EXCEL_EXPORT); true }
                Key.Seven -> { viewModel.navigateTo(AppDestination.PDF_PRINT); true }
                Key.Eight -> { viewModel.navigateTo(AppDestination.SETTINGS); true }
                else -> false
            }
        } else {
            false
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .then(keyboardShortcutModifier)
    ) {
        val isLaptopScreen = maxWidth >= 1024.dp
        val isTabletScreen = maxWidth >= 720.dp && !isLaptopScreen
        val isWideScreen = maxWidth >= 720.dp

        val mainContent: @Composable (Modifier) -> Unit = { contentModifier ->
            Box(
                modifier = contentModifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                when (currentDestination) {
                    AppDestination.DASHBOARD -> DashboardScreen(
                        companyName = settings.companyName,
                        savedPosState = savedPosState,
                        isWideScreen = isWideScreen,
                        onUploadNewPo = { viewModel.navigateTo(AppDestination.UPLOAD_PO) },
                        onOpenSavedPos = { viewModel.navigateTo(AppDestination.SAVED_POS) },
                        onOpenPo = { po, target -> viewModel.openSavedPo(po, target) }
                    )

                    AppDestination.UPLOAD_PO -> UploadPoScreen(
                        uploadState = uploadState,
                        isLaptopScreen = isLaptopScreen,
                        onParseFile = { name, provider -> viewModel.parseUploadedFile(name, provider) },
                        onSelectSheet = { viewModel.selectUploadSheet(it) },
                        onUpdateHeaderRow = { viewModel.updateHeaderRowIndex(it) },
                        onUpdateMetadata = { viewModel.updateUploadMetadata(it) },
                        onUpdateMapping = { viewModel.updateColumnMapping(it) },
                        onToggleSizeColumn = { viewModel.toggleSizeMatrixColumn(it) },
                        onClearStaging = { viewModel.clearUploadStaging() },
                        onTransferToLslFormat = { viewModel.transferUploadedPo() }
                    )

                    AppDestination.PO_TRANSFER -> PoTransferScreen(
                        activeState = activePoState,
                        onGoToUpload = { viewModel.navigateTo(AppDestination.UPLOAD_PO) },
                        onGoToSavedPos = { viewModel.navigateTo(AppDestination.SAVED_POS) },
                        onGoToBreakdown = { viewModel.navigateTo(AppDestination.PO_BREAKDOWN) },
                        onGoToExcelExport = { viewModel.navigateTo(AppDestination.EXCEL_EXPORT) },
                        onGoToPdfPrint = { viewModel.navigateTo(AppDestination.PDF_PRINT) },
                        onUpdateMetadata = { viewModel.updateActivePoMetadata(it) },
                        onUpdateLineItem = { id, fn -> viewModel.updateLineItem(id, fn) },
                        onUpdateLineSizeQty = { id, sz, q -> viewModel.updateLineSizeQuantity(id, sz, q) },
                        onAlignDeclaredQty = { id -> viewModel.alignDeclaredQtyToSizeSum(id) },
                        onAddLineItem = { s, d, c, g, p, del, dest ->
                            viewModel.addLineItem(s, d, c, g, p, del, dest)
                        },
                        onRemoveLineItem = { viewModel.removeLineItem(it) },
                        onAddSizeColumn = { viewModel.addSizeColumn(it) },
                        onRemoveSizeColumn = { viewModel.removeSizeColumn(it) },
                        onSavePo = { verify, force -> viewModel.requestSaveActivePo(verify, force) },
                        onDismissDuplicateDialog = { viewModel.dismissDuplicateWarning() }
                    )

                    AppDestination.PO_BREAKDOWN -> PoBreakdownScreen(
                        activeState = activePoState,
                        savedOriginalSource = savedOriginalSource,
                        auditLogs = activeAuditLogs,
                        onGoToUpload = { viewModel.navigateTo(AppDestination.UPLOAD_PO) },
                        onGoToSavedPos = { viewModel.navigateTo(AppDestination.SAVED_POS) },
                        onEditInTransfer = { viewModel.navigateTo(AppDestination.PO_TRANSFER) },
                        onSavePo = { viewModel.requestSaveActivePo(false, false) },
                        onGoToExcelExport = { viewModel.navigateTo(AppDestination.EXCEL_EXPORT) },
                        onGoToPdfPrint = { viewModel.navigateTo(AppDestination.PDF_PRINT) }
                    )

                    AppDestination.SAVED_POS -> SavedPosScreen(
                        savedPosState = savedPosState,
                        isLaptopScreen = isLaptopScreen,
                        onUploadNewPo = { viewModel.navigateTo(AppDestination.UPLOAD_PO) },
                        onOpenPo = { po, target -> viewModel.openSavedPo(po, target) },
                        onDeletePo = { viewModel.deleteSavedPo(it) }
                    )

                    AppDestination.EXCEL_EXPORT -> ExcelExportScreen(
                        activeState = activePoState,
                        companyName = settings.companyName,
                        onGoToUpload = { viewModel.navigateTo(AppDestination.UPLOAD_PO) },
                        onGoToSavedPos = { viewModel.navigateTo(AppDestination.SAVED_POS) },
                        onGoToPdfPrint = { viewModel.navigateTo(AppDestination.PDF_PRINT) },
                        onExportCompleted = { action, summary -> viewModel.recordExportAudit(action, summary) },
                        onShowMessage = { viewModel.showBanner(it) }
                    )

                    AppDestination.PDF_PRINT -> PdfPrintScreen(
                        activeState = activePoState,
                        companyName = settings.companyName,
                        isLaptopScreen = isLaptopScreen,
                        onGoToUpload = { viewModel.navigateTo(AppDestination.UPLOAD_PO) },
                        onGoToSavedPos = { viewModel.navigateTo(AppDestination.SAVED_POS) },
                        onSavePo = { viewModel.requestSaveActivePo(false, false) },
                        onAuditEvent = { action, summary -> viewModel.recordExportAudit(action, summary) },
                        onShowMessage = { viewModel.showBanner(it) }
                    )

                    AppDestination.SETTINGS -> SettingsScreen(
                        settings = settings,
                        userEmail = user.email.orEmpty(),
                        userDisplayName = user.displayName.orEmpty(),
                        onSaveSettings = { viewModel.saveMerchandiserSettings(it) },
                        onSignOut = {
                            signOutUser(credentialManager = credentialManager, scope = scope)
                        }
                    )
                }
            }
        }

        when {
            isLaptopScreen -> {
                // Laptop / Chromebook / DeX Full Desktop Layout: Permanent Sidebar + Widescreen Workspace
                PermanentNavigationDrawer(
                    drawerContent = {
                        PermanentDrawerSheet(
                            modifier = Modifier.width(272.dp),
                            drawerContainerColor = MaterialTheme.colorScheme.surface
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(LslForestGreenDark)
                                    .padding(horizontal = 20.dp, vertical = 22.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = settings.companyName.uppercase(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = LslPureWhite.copy(alpha = 0.8f)
                                )
                                Text(
                                    text = "LSL PO Manager",
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    color = LslPureWhite
                                )
                                Text(
                                    text = user.email ?: "Laptop Merchandiser Mode",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = LslPureWhite.copy(alpha = 0.78f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState())
                                    .padding(vertical = 8.dp)
                            ) {
                                AppDestination.entries.forEachIndexed { idx, dest ->
                                    val selected = currentDestination == dest
                                    NavigationDrawerItem(
                                        label = {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text("${idx + 1}. ${dest.title}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(
                                                    text = "Ctrl+${idx + 1}",
                                                    style = MonoNumericStyle,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                                                )
                                            }
                                        },
                                        selected = selected,
                                        onClick = { viewModel.navigateTo(dest) },
                                        icon = { Icon(destinationIcon(dest), contentDescription = dest.title) },
                                        colors = NavigationDrawerItemDefaults.colors(
                                            selectedContainerColor = LslMintTint,
                                            selectedIconColor = LslForestGreen,
                                            selectedTextColor = LslForestGreen
                                        ),
                                        modifier = Modifier
                                            .padding(horizontal = 12.dp, vertical = 2.dp)
                                            .testTag("laptop_nav_${dest.name.lowercase()}")
                                    )
                                    if (idx == 4) {
                                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                                    }
                                }
                            }

                            // Active PO Pill & Keyboard Shortcuts Footer
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                activePoState.po?.let { active ->
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = LslMintTint,
                                        border = BorderStroke(1.dp, LslMintBorder),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Text("ACTIVE WORKSPACE PO", style = MaterialTheme.typography.labelSmall, color = LslForestGreen)
                                            Text(
                                                text = "${active.poNumber} • ${active.buyerName}",
                                                style = MaterialTheme.typography.labelLarge,
                                                color = LslForestGreenDark,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(Icons.Outlined.Keyboard, contentDescription = "Keyboard shortcuts", tint = LslForestGreen)
                                        Text(
                                            text = "Ctrl+U Upload • Ctrl+S Save\nCtrl+E Excel • Ctrl+P Print",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                ) {
                    Scaffold(
                        snackbarHost = { SnackbarHost(snackbarHostState) },
                        topBar = {
                            TopAppBar(
                                title = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Text(
                                            text = currentDestination.title,
                                            style = MaterialTheme.typography.titleLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        activePoState.po?.let { active ->
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = LslPureWhite.copy(alpha = 0.16f)
                                            ) {
                                                Text(
                                                    text = "PO: ${active.poNumber}",
                                                    style = MonoNumericStyle,
                                                    color = LslPureWhite,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                                )
                                            }
                                        }
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = LslForestGreen,
                                    titleContentColor = LslPureWhite
                                )
                            )
                        }
                    ) { innerPadding ->
                        mainContent(Modifier.padding(innerPadding))
                    }
                }
            }

            isTabletScreen -> {
                // Tablet Medium Layout: Side NavigationRail (all 8 sections)
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    topBar = {
                        TopAppBar(
                            title = {
                                Column {
                                    Text(
                                        text = currentDestination.title,
                                        style = MaterialTheme.typography.titleLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = settings.companyName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = LslPureWhite.copy(alpha = 0.8f)
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = LslForestGreen,
                                titleContentColor = LslPureWhite
                            )
                        )
                    }
                ) { innerPadding ->
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        NavigationRail(
                            containerColor = MaterialTheme.colorScheme.surface,
                            modifier = Modifier
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                                .padding(vertical = 8.dp)
                        ) {
                            AppDestination.entries.forEach { dest ->
                                val selected = currentDestination == dest
                                NavigationRailItem(
                                    selected = selected,
                                    onClick = { viewModel.navigateTo(dest) },
                                    icon = { Icon(destinationIcon(dest), contentDescription = dest.title) },
                                    label = { Text(dest.shortTitle, maxLines = 1) },
                                    colors = NavigationRailItemDefaults.colors(
                                        selectedIconColor = LslForestGreen,
                                        selectedTextColor = LslForestGreen,
                                        indicatorColor = LslMintTint
                                    ),
                                    modifier = Modifier.testTag("nav_rail_${dest.name.lowercase()}")
                                )
                            }
                        }
                        mainContent(Modifier.weight(1f))
                    }
                }
            }

            else -> {
                // Compact Mobile Layout: ModalNavigationDrawer (all 8 menu items) + Bottom NavigationBar
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        ModalDrawerSheet(
                            modifier = Modifier.width(290.dp),
                            drawerContainerColor = MaterialTheme.colorScheme.surface
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(LslForestGreenDark)
                                    .padding(horizontal = 20.dp, vertical = 24.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = settings.companyName.uppercase(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = LslPureWhite.copy(alpha = 0.8f)
                                )
                                Text(
                                    text = "LSL PO Manager",
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    color = LslPureWhite
                                )
                                Text(
                                    text = user.email ?: "Merchandiser Workspace",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = LslPureWhite.copy(alpha = 0.75f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                AppDestination.entries.forEachIndexed { idx, dest ->
                                    val selected = currentDestination == dest
                                    NavigationDrawerItem(
                                        label = { Text("${idx + 1}. ${dest.title}") },
                                        selected = selected,
                                        onClick = {
                                            viewModel.navigateTo(dest)
                                            scope.launch { drawerState.close() }
                                        },
                                        icon = { Icon(destinationIcon(dest), contentDescription = dest.title) },
                                        colors = NavigationDrawerItemDefaults.colors(
                                            selectedContainerColor = LslMintTint,
                                            selectedIconColor = LslForestGreen,
                                            selectedTextColor = LslForestGreen
                                        ),
                                        modifier = Modifier
                                            .padding(horizontal = 12.dp, vertical = 2.dp)
                                            .testTag("drawer_nav_${dest.name.lowercase()}")
                                    )
                                    if (idx == 4) {
                                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                                    }
                                }
                            }
                        }
                    }
                ) {
                    Scaffold(
                        snackbarHost = { SnackbarHost(snackbarHostState) },
                        topBar = {
                            TopAppBar(
                                navigationIcon = {
                                    IconButton(
                                        onClick = { scope.launch { drawerState.open() } },
                                        modifier = Modifier.testTag("open_navigation_drawer_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Menu,
                                            contentDescription = "Open LSL Navigation Menu",
                                            tint = LslPureWhite
                                        )
                                    }
                                },
                                title = {
                                    Column {
                                        Text(
                                            text = currentDestination.title,
                                            style = MaterialTheme.typography.titleLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = settings.companyName,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = LslPureWhite.copy(alpha = 0.82f)
                                        )
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = LslForestGreen,
                                    titleContentColor = LslPureWhite
                                )
                            )
                        },
                        bottomBar = {
                            NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surface,
                                windowInsets = WindowInsets.navigationBars
                            ) {
                                bottomBarDestinations.forEach { dest ->
                                    val selected = currentDestination == dest
                                    NavigationBarItem(
                                        selected = selected,
                                        onClick = { viewModel.navigateTo(dest) },
                                        icon = { Icon(destinationIcon(dest), contentDescription = dest.title) },
                                        label = { Text(dest.shortTitle, maxLines = 1) },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = LslForestGreen,
                                            selectedTextColor = LslForestGreen,
                                            indicatorColor = LslMintTint
                                        ),
                                        modifier = Modifier.testTag("bottom_nav_${dest.name.lowercase()}")
                                    )
                                }
                            }
                        }
                    ) { innerPadding ->
                        mainContent(Modifier.padding(innerPadding))
                    }
                }
            }
        }
    }
}
