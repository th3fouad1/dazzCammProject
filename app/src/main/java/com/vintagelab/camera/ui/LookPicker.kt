package com.vintagelab.camera.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Horizontal list of looks; names resolved from each preset's `displayNameRes` key. */
@Composable
fun LookPicker(looks: List<LookItem>, selectedId: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        items(looks, key = { it.id }) { look ->
            val selected = look.id == selectedId
            val shape = RoundedCornerShape(16.dp)
            Text(
                text = lookName(look),
                color = if (selected) Color.Black else Color.White,
                modifier = Modifier
                    .background(if (selected) Color(0xFFFF8A1F) else Color.White.copy(alpha = 0.12f), shape)
                    .border(1.dp, Color.White.copy(alpha = if (selected) 0f else 0.3f), shape)
                    .clickable(role = Role.RadioButton) { onSelect(look.id) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/** presets.json references string resources by key so looks stay data-driven. */
@SuppressLint("DiscouragedApi")
@Composable
private fun lookName(look: LookItem): String {
    val resources = LocalResources.current
    val pkg = LocalContext.current.packageName
    val id = resources.getIdentifier(look.displayNameRes, "string", pkg)
    return if (id != 0) resources.getString(id) else look.id
}
