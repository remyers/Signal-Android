package org.thoughtcrime.securesms.megaphone;

import android.content.Context;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.annimon.stream.Collectors;
import com.annimon.stream.Stream;

import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.MegaphoneDatabase;
import org.thoughtcrime.securesms.database.model.MegaphoneRecord;
import org.thoughtcrime.securesms.megaphone.Megaphones.Event;
import org.thoughtcrime.securesms.util.concurrent.SignalExecutors;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Synchronization of data structures is done using a serial executor. Do not access or change
 * data structures or fields on anything except the executor.
 */
public class MegaphoneRepository {

  private final Context                     context;
  private final Executor                    executor;
  private final MegaphoneDatabase           database;
  private final Map<Event, MegaphoneRecord> databaseCache;

  private boolean enabled;

  public MegaphoneRepository(@NonNull Context context) {
    this.context       = context;
    this.executor      = SignalExecutors.SERIAL;
    this.database      = DatabaseFactory.getMegaphoneDatabase(context);
    this.databaseCache = new HashMap<>();

    executor.execute(this::init);
  }

  /**
   * Marks any megaphones a new user shouldn't see as "finished".
   */
  @MainThread
  public void onFirstEverAppLaunch() {
    executor.execute(() -> {
      // Future megaphones we don't want to show to new users should get marked as finished here.
    });
  }

  @MainThread
  public void onAppForegrounded() {
    executor.execute(() -> enabled = true);
  }

  @MainThread
  public void getNextMegaphone(@NonNull Callback<Megaphone> callback) {
    executor.execute(() -> {
      if (enabled) {
        callback.onResult(Megaphones.getNextMegaphone(context, databaseCache));
      } else {
        callback.onResult(null);
      }
    });
  }

  @MainThread
  public void markSeen(@NonNull Megaphone megaphone) {
    long lastSeen = System.currentTimeMillis();

    executor.execute(() -> {
      Event           event  = megaphone.getEvent();
      MegaphoneRecord record = getRecord(event);

      if (megaphone.getMaxAppearances() != Megaphone.UNLIMITED &&
          record.getSeenCount() + 1 >= megaphone.getMaxAppearances())
      {
        database.markFinished(event);
      } else {
        database.markSeen(event, record.getSeenCount() + 1, lastSeen);
      }

      enabled = false;
      resetDatabaseCache();
    });
  }

  @MainThread
  public void markFinished(@NonNull Event event) {
    executor.execute(() -> {
      database.markFinished(event);
      resetDatabaseCache();
    });
  }

  @WorkerThread
  private void init() {
    List<MegaphoneRecord> records = database.getAll();
    Set<Event>            events  = Stream.of(records).map(MegaphoneRecord::getEvent).collect(Collectors.toSet());
    Set<Event>            missing = Stream.of(Megaphones.Event.values()).filterNot(events::contains).collect(Collectors.toSet());

    database.insert(missing);
    resetDatabaseCache();
  }

  @WorkerThread
  private @NonNull MegaphoneRecord getRecord(@NonNull Event event) {
    //noinspection ConstantConditions
    return databaseCache.get(event);
  }

  @WorkerThread
  private void resetDatabaseCache() {
    databaseCache.clear();
    databaseCache.putAll(Stream.of(database.getAll()).collect(Collectors.toMap(MegaphoneRecord::getEvent, m -> m)));
  }

  public interface Callback<E> {
    void onResult(E result);
  }
}
