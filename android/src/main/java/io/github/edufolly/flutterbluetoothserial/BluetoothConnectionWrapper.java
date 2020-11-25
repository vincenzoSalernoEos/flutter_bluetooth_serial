package io.github.edufolly.flutterbluetoothserial;

import android.bluetooth.BluetoothAdapter;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.Executors;

import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;

public class BluetoothConnectionWrapper extends BluetoothConnection {

	private final int id;
	private final Runnable onCancelCallBack;

	protected EventChannel.EventSink readSink;

	protected EventChannel readChannel;

	private final Handler uiThreadHandler = new Handler(Looper.getMainLooper());

	private final BluetoothConnectionWrapper self = this;

	public BluetoothConnectionWrapper(int id, BluetoothAdapter adapter, BinaryMessenger messenger, Runnable onCancelCallBack) {
		super(adapter);
		this.id = id;

		this.onCancelCallBack = onCancelCallBack;

		readChannel = new EventChannel(messenger, Constants.READ_CHANNEL + id);
		readChannel.setStreamHandler(new ReadChannel());
	}

	@Override
	protected void onRead(byte[] buffer) {
//		activity.runOnUiThread(() -> {
		uiThreadHandler.post(() -> {
			if (readSink != null) {
				readSink.success(buffer);
			}
		});
	}

	@Override
	protected void onDisconnected(boolean byRemote) {
//		activity.runOnUiThread(() -> {
		uiThreadHandler.post(() -> {
			if (byRemote) {
				Log.d(Constants.TAG, "onDisconnected by remote (id: " + id + ")");
				if (readSink != null) {
					readSink.endOfStream();
					readSink = null;
				}
			}
			else {
				Log.d(Constants.TAG, "onDisconnected by local (id: " + id + ")");
			}
		});
	}

	class ReadChannel implements EventChannel.StreamHandler {

		/**
		 * Handles a request to set up an event stream.
		 *
		 * <p>Any uncaught exception thrown by this method will be caught by the channel implementation
		 * and logged. An error result message will be sent back to Flutter.
		 *
		 * @param arguments stream configuration arguments, possibly null.
		 * @param eventSink an {@link EventChannel.EventSink} for emitting events to the Flutter receiver.
		 */
		@Override
		public void onListen(Object arguments, EventChannel.EventSink eventSink) {
			readSink = eventSink;
		}

		/**
		 * Handles a request to tear down the most recently created event stream.
		 *
		 * <p>Any uncaught exception thrown by this method will be caught by the channel implementation
		 * and logged. An error result message will be sent back to Flutter.
		 *
		 * <p>The channel implementation may call this method with null arguments to separate a pair of
		 * two consecutive set up requests. Such request pairs may occur during Flutter hot restart. Any
		 * uncaught exception thrown in this situation will be logged without notifying Flutter.
		 *
		 * @param arguments stream configuration arguments, possibly null.
		 */
		@Override
		public void onCancel(Object arguments) {
			// If canceled by local, disconnects - in other case, by remote, does nothing
			self.disconnect();

			// True dispose
			//AsyncTask.execute replaced because of deprecation
			Executors.newSingleThreadExecutor().execute(() -> {
				readChannel.setStreamHandler(null);

				onCancelCallBack.run();

				Log.d(Constants.TAG, "Disconnected (id: " + id + ")");
			});
		}
	}

}
