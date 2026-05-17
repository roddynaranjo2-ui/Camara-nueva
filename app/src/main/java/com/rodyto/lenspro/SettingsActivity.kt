@Composable
private fun AccentRow(
    style: AccentStyle,
    selected: Boolean,
    palette: GlassPalette,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Swatch del color con anillo de selección
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(
                    if (style == AccentStyle.OBSIDIAN || style == AccentStyle.GRAPHITE)
                        style.accent
                    else
                        style.accent
                )
                .border(
                    width = if (selected) 2.5.dp else 1.dp,
                    color = if (selected) palette.onGlass else palette.borderSoft,
                    shape = CircleShape
                )
        ) {
            // Brillo interior sutil
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.35f))
                    .align(Alignment.TopStart)
                    .padding(start = 5.dp, top = 4.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = style.label,
                color = palette.onGlass,
                fontSize = 15.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
            // Etiqueta de categoría
            val category = when {
                style.isPastel -> "Pastel"
                style.isMonochrome -> "Monocromático"
                else -> "Vibrante"
            }
            Text(
                text = category,
                color = palette.onGlassSecondary,
                fontSize = 11.sp
            )
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(palette.accent),
                contentAlignment = Alignment.Center
            ) {
                Text("✓", color = palette.onAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}