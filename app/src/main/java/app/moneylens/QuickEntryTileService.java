package app.moneylens;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile that launches MoneyLens Smart Quick Entry directly. */
public final class QuickEntryTileService extends TileService {
  @Override public void onStartListening() {
    super.onStartListening();
    Tile t = getQsTile();
    if (t == null) return;
    boolean ms = Vault.hasMeta(this) && "ms".equals(Vault.fastStr(this, "lang", "en"));
    boolean locked = Vault.hasMeta(this) && Vault.fastBool(this, "appLock", false);
    t.setLabel(ms ? "Rekod Pantas" : "Quick Expense");
    if (Build.VERSION.SDK_INT >= 29) {
      t.setSubtitle(locked ? (ms ? "Buka kunci" : "Unlock") : "MoneyLens");
    }
    t.setIcon(Icon.createWithResource(this, R.drawable.ic_notification));
    t.setState(Tile.STATE_INACTIVE);
    t.updateTile();
  }

  @Override public void onClick() {
    super.onClick();
    Intent i = new Intent(this, MainActivity.class)
        .putExtra("screen", "quick")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    if (Build.VERSION.SDK_INT >= 34) {
      PendingIntent pi = PendingIntent.getActivity(
          this, 2120, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
      startActivityAndCollapse(pi);
    } else {
      startLegacyActivity(i);
    }
  }

  @SuppressWarnings("deprecation")
  private void startLegacyActivity(Intent i) {
    startActivityAndCollapse(i);
  }
}
