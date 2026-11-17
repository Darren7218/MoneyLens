package app.moneylens;

import java.util.*;

/** Pure Android-free launch/resume screen routing state helper. */
public final class ScreenRouter {
  private static final Set<String> VALID_SCREENS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
      "home", "inbox", "plan", "quick", "scan", "favorites", "transactions", "review"
  )));

  public static String resolveExtraScreen(String extra) {
    return extra != null && VALID_SCREENS.contains(extra) ? extra : "";
  }

  /** Clears pending launch target after dispatching so subsequent onResume/unlock keeps user's active screen. */
  public static String consumePendingScreen() {
    return "";
  }
}
