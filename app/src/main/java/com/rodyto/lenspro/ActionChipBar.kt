@Composable
fun ModeSelectorIos(
    mode: String, palette: GlassPalette,
    onModeChange: (String) -> Unit, modifier: Modifier = Modifier
) {
    val modes = listOf("FOTO", "VIDEO")
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(26.dp))
            .liquidGlass(palette, RoundedCornerShape(26.dp), strong = false)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        modes.forEach { item ->
            ModeLabel(
                text = item, selected = mode == item, palette = palette,
                onClick = { onModeChange(item) }
            )
        }
    }
}

@Composable
private fun ModeLabel(text: String, selected: Boolean, palette: GlassPalette, onClick: () -> Unit) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.96f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "mode_scale"
    )
    Box(
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(22.dp))
            .background(
                if (selected) palette.accent.copy(alpha = 0.98f)
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) palette.onAccent else palette.onGlassSecondary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            letterSpacing = 0.5.sp
        )
    }
}