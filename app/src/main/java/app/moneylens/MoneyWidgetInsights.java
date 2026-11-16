package app.moneylens;

import android.appwidget.*;

/** Dedicated 4x3 Budgets & Activity insights widget with 7-day bars, budget progress & latest activity. */
public final class MoneyWidgetInsights extends MoneyWidget {
  @Override protected boolean isAdaptiveCardStyle() { return true; }
  @Override protected int defaultWidthDp() { return 260; }
  @Override protected int defaultHeightDp() { return 240; }
}
