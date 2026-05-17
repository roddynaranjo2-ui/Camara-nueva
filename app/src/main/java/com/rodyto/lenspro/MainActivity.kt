@Composable
fun SettingsPanel(
    palette: GlassPalette,
    maxHeightFraction: Float = 0.85f,
    flashMode: FlashMode,
    onToggleFlash: () -> Unit,
    hdrOn: Boolean,
    onToggleHdr: () -> Unit,
    gridOn: Boolean,
    onToggleGrid: () -> Unit,
    soundOn: Boolean,
    onToggleSound: () -> Unit,
    hapticsOn: Boolean,
    onToggleHaptics: () -> Unit,
    hevcOn: Boolean,
    onToggleHevc: () -> Unit,
    histogramOn: Boolean,
    onToggleHistogram: () -> Unit,
    horizonOn: Boolean,
    onToggleHorizon: () -> Unit,
    rawOn: Boolean,
    onToggleRaw: () -> Unit,
    timerSec: Int,
    onCycleTimer: () -> Unit,
    darkPref: Boolean?,
    onCycleTheme: () -> Unit,
    accentStyleLabel: String,
    onCycleAccentStyle: () -> Unit,
    manualAspect: PreviewAspect?,
    onAspectChange: (PreviewAspect?) -> Unit,
    onOpenFullSettings: () -> Unit,
    onClose: () -> Unit,
    onAnyAction: () -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.48f))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.BottomCenter
    ) {
        val maxPanelHeight = maxHeight * maxHeightFraction

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxPanelHeight)
                .clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                .background(palette.ultraBase)
                .border(
                    width = 0.8.dp,
                    color = palette.ultraStroke,
                    shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
                )
                .clickable(enabled = false, onClick = {}),
            verticalArrangement = Arrangement.Top
        ) {
            // Handle pill
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 12.dp, bottom = 2.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(palette.onGlassSecondary.copy(alpha = 0.30f))
            )

            // Título fijo
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Ajustes",
                    color = palette.onGlass,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(palette.onGlassSecondary.copy(alpha = 0.12f))
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center
                ) {
                    Text("×", color = palette.onGlassSecondary, fontSize = 20.sp, fontWeight = FontWeight.Light)
                }
            }

            // Contenido scrollable
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ── Captura ───────────────────────────────────────────────
                item { SectionLabel("Captura", palette) }
                item {
                    SettingsActionRow(
                        label = "Flash",
                        value = when (flashMode) {
                            FlashMode.OFF -> "Off"
                            FlashMode.AUTO -> "Auto"
                            FlashMode.ON -> "On"
                        },
                        palette = palette
                    ) { onAnyAction(); onToggleFlash() }
                }
                item { SettingsRow("HDR", hdrOn, palette) { onAnyAction(); onToggleHdr() } }
                item { SettingsRow("Formato RAW (DNG)", rawOn, palette) { onAnyAction(); onToggleRaw() } }
                item { SettingsRow("Codec HEVC (H.265)", hevcOn, palette) { onAnyAction(); onToggleHevc() } }
                item {
                    SettingsActionRow(
                        label = "Temporizador",
                        value = when (timerSec) { 3 -> "3 s"; 10 -> "10 s"; else -> "Off" },
                        palette = palette
                    ) { onAnyAction(); onCycleTimer() }
                }

                // ── Composición ───────────────────────────────────────────
                item { SectionLabel("Composición", palette) }
                item { SettingsRow("Cuadrícula 3×3", gridOn, palette) { onAnyAction(); onToggleGrid() } }
                item {
                    SettingsRow("Histograma en tiempo real", histogramOn, palette) {
                        onAnyAction(); onToggleHistogram()
                    }
                }
                item {
                    SettingsRow("Horizonte artificial", horizonOn, palette) {
                        onAnyAction(); onToggleHorizon()
                    }
                }

                // ── Sonido / vibración ────────────────────────────────────
                item { SectionLabel("Sonido y vibración", palette) }
                item {
                    SettingsRow("Sonido del obturador", soundOn, palette) {
                        onAnyAction(); onToggleSound()
                    }
                }
                item {
                    SettingsRow("Vibración háptica", hapticsOn, palette) {
                        onAnyAction(); onToggleHaptics()
                    }
                }

                // ── Apariencia ────────────────────────────────────────────
                item { SectionLabel("Apariencia", palette) }
                item {
                    SettingsActionRow(
                        label = "Tema",
                        value = when (darkPref) { true -> "Oscuro"; false -> "Claro"; null -> "Sistema" },
                        palette = palette
                    ) { onAnyAction(); onCycleTheme() }
                }

                // ── Selector visual de color ──────────────────────────────
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Color de interfaz",
                            color = palette.onGlass,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = accentStyleLabel,
                            color = palette.accent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        // Fila 1: primeros 7 colores
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AccentStyle.entries.take(7).forEach { style ->
                                val isSelected = style.label == accentStyleLabel
                                Box(
                                    modifier = Modifier
                                        .size(if (isSelected) 34.dp else 28.dp)
                                        .clip(CircleShape)
                                        .background(style.accent)
                                        .border(
                                            width = if (isSelected) 2.5.dp else 0.dp,
                                            color = if (isSelected) palette.onGlass else Color.Transparent,
                                            shape = CircleShape
                                        )
                                        .clickable { onAnyAction(); onCycleAccentStyle() }
                                )
                            }
                        }
                        // Fila 2: colores restantes (pasteles)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AccentStyle.entries.drop(7).forEach { style ->
                                val isSelected = style.label == accentStyleLabel
                                Box(
                                    modifier = Modifier
                                        .size(if (isSelected) 34.dp else 28.dp)
                                        .clip(CircleShape)
                                        .background(style.accent)
                                        .border(
                                            width = if (isSelected) 2.5.dp else 0.dp,
                                            color = if (isSelected) palette.onGlass else Color.Transparent,
                                            shape = CircleShape
                                        )
                                        .clickable { onAnyAction(); onCycleAccentStyle() }
                                )
                            }
                        }
                    }
                }

                // ── Relación de aspecto ───────────────────────────────────
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Relación de aspecto",
                            color = palette.onGlass,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val options = listOf(
                                "Auto" to null,
                                "3:4" to PreviewAspect.RATIO_3_4,
                                "9:16" to PreviewAspect.RATIO_9_16,
                                "1:1" to PreviewAspect.RATIO_1_1,
                                "Full" to PreviewAspect.RATIO_FULL
                            )
                            options.forEach { (label, value) ->
                                val selected = manualAspect == value
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(
                                            if (selected) palette.accent else Color.Transparent
                                        )
                                        .border(
                                            width = if (selected) 0.dp else 0.6.dp,
                                            color = if (selected) Color.Transparent else palette.borderSoft,
                                            shape = RoundedCornerShape(16.dp)
                                        )
                                        .clickable {
                                            onAnyAction()
                                            onAspectChange(value)
                                        }
                                        .padding(horizontal = 10.dp, vertical = 7.dp)
                                ) {
                                    Text(
                                        text = label,
                                        color = if (selected) palette.onAccent else palette.onGlass,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }

                // ── Acceso a SettingsActivity ─────────────────────────────
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .liquidGlass(palette, RoundedCornerShape(18.dp), strong = true)
                            .clickable(onClick = onOpenFullSettings)
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LensIcon(icon = LensIcons.More, tint = palette.accent, size = 20.dp)
                            Spacer(Modifier.size(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Configuración avanzada",
                                    color = palette.onGlass,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "RAW, zoom suave, histograma y más",
                                    color = palette.onGlassSecondary,
                                    fontSize = 12.sp
                                )
                            }
                            LensIcon(
                                icon = LensIcons.ChevronRight,
                                tint = palette.onGlassSecondary,
                                size = 18.dp
                            )
                        }
                    }
                }

                item { Spacer(Modifier.height(6.dp)) }
            }

            // Botón Cerrar fijo al fondo
            Button(
                onClick = onClose,
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.accent,
                    contentColor = palette.onAccent
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Text("Cerrar", fontWeight = FontWeight.Bold)
            }
        }
    }
}