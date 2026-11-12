package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The tvos27 dock's geometry: even spacing, concentric corners, and room for the focused tile (TvDesign). */
public class TvosLayoutTest {

    private static final int W = 1920;

    @Test public void aFocusedEndTileKeepsAFullGapFromTheGlass() {
        for (int columns = 3; columns <= 9; columns++) {
            float gap = TvDesign.Layout.dockGap(columns), pad = TvDesign.Layout.dockPad(columns);
            float inner = W - 2 * (TvDesign.Layout.SAFE_SIDE + pad - gap / 2f);
            float tile = inner / columns - gap;
            float growth = tile * (TvDesign.Focus.SCALE - 1f) / 2f;
            assertTrue(columns + " columns: " + (pad - growth) + " vs gap " + gap, pad - growth >= gap - 0.01f);
            assertTrue(gap >= TvDesign.Layout.GUTTER);
            assertTrue("neighbours, both focused in turn, never touch", growth + TvDesign.Layout.DOCK_CLEARANCE <= gap);
        }
    }

    @Test public void theDockEndsExactlyAtTheSafeAreaWithSixColumns() {
        int columns = 6;
        float gap = TvDesign.Layout.dockGap(columns), pad = TvDesign.Layout.dockPad(columns);
        float gridLeft = TvDesign.Layout.SAFE_SIDE + pad - gap / 2f;
        float inner = W - 2 * gridLeft;
        float tile = inner / columns - gap;
        float firstTileLeft = gridLeft + gap / 2f;
        assertEquals(TvDesign.Layout.SAFE_SIDE, firstTileLeft - pad, 0.01f);
        float lastTileRight = gridLeft + inner - gap / 2f;
        assertEquals(W - TvDesign.Layout.SAFE_SIDE, lastTileRight + pad, 0.01f);
        assertTrue("six columns stay a usable size: " + tile, tile > 200);
    }

    @Test public void dockCornersAreConcentricWithTheTiles() {
        float tile = 246.7f, pad = TvDesign.Layout.dockGap(6);
        assertEquals(TvDesign.Radius.card(tile) + pad, TvDesign.Radius.dock(tile, pad), 0.001f);
        assertEquals(TvDesign.Radius.CARD, TvDesign.Radius.card(TvDesign.Radius.CARD_W), 0.001f);
    }

    @Test public void tileWidthWithAGapLeavesExactlyOneGapBetweenNeighbours() {
        int inner = 1720, gap = 40;
        int w = AppAdapter.tileWidthPx(inner, 0, 6, 0, false, true, 2f, gap);
        assertEquals(inner / 6 - gap, w);
        assertEquals("the default look is unchanged", AppAdapter.tileWidthPx(inner, 0, 6, 0, false, true, 2f),
                AppAdapter.tileWidthPx(inner, 0, 6, 0, false, true, 2f, 0));
    }
}
