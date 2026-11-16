package app.moneylens;

import android.appwidget.*;

/** Dedicated 2x1 compact safe-to-spend & quick-add pill widget (adapts to app Light/Dark theme and resizes smoothly). */
public final class MoneyWidgetCompact extends MoneyWidget {
  @Override protected boolean isAdaptiveCardStyle() { return true; }
  @Override protected int defaultWidthDp() { return 120; }
  @Override protected int defaultHeightDp() { return 48; }
}
