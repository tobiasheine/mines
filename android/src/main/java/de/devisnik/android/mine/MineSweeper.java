package de.devisnik.android.mine;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.DialogInterface.OnShowListener;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Spinner;
import de.devisnik.android.mine.data.ReadGameCommand;
import de.devisnik.android.mine.data.SaveGameCommand;
import de.devisnik.android.mine.device.IDevice;
import de.devisnik.mine.IGame;
import de.devisnik.mine.MinesGameAdapter;

public class MineSweeper extends Activity {

	private static final int HIGHSCORES_REQUEST = 42;
	private static final String SKIP_CACHE = "skip_cache";
	private static final int DIALOG_NEW_GAME = 1;
	private static final int DIALOG_INTRO = 4;
	private static final String GAME_CACHE_FILE = "game.cache";
	private static final Logger LOGGER = new Logger(MineSweeper.class);

	private class NewGameDialogBuilder extends Builder {

		public NewGameDialogBuilder() {
			super(MineSweeper.this);
			// setTheme(android.R.style.Theme_Dialog);
			ViewGroup layout = (ViewGroup) getLayoutInflater().inflate(R.layout.new_game, null);
			final PreferenceSpinnerController boardSpinnerController = createSpinnerController(
					R.string.prefkey_board_size, R.array.sizes_values, R.id.BoardSpinner, layout);
			final PreferenceSpinnerController levelSpinnerController = createSpinnerController(
					R.string.prefkey_game_level, R.array.levels_values, R.id.LevelSpinner, layout);

			setView(layout);
			setTitle(R.string.another_game);
			setPositiveButton(R.string.another_game_yes, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(final DialogInterface dialog, final int id) {
					boardSpinnerController.updatePreference();
					levelSpinnerController.updatePreference();
					restartWithNewGame();
				}
			});
			setNegativeButton(R.string.another_game_no, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(final DialogInterface dialog, final int id) {
					boardSpinnerController.reset();
					levelSpinnerController.reset();
				}
			});
			setOnCancelListener(new OnCancelListener() {

				@Override
				public void onCancel(final DialogInterface dialog) {
					boardSpinnerController.reset();
					levelSpinnerController.reset();
				}
			});
		}

		private PreferenceSpinnerController createSpinnerController(final int prefKeyId, final int valueArrayId,
				final int viewId, final ViewGroup layout) {
			Spinner spinner = (Spinner) layout.findViewById(viewId);
			return new PreferenceSpinnerController(prefKeyId, valueArrayId, spinner);
		}
	}

	private BoardController boardController;
	private BombsController bombsController;
	private MessagesController messagesController;
	private TimerController timerController;
	private Settings settings;
	private IGame game;
	private GameTimer gameTimer;
	private GameListener gameListener;
	private boolean skipCache;
	private Notifier notifier;
	private IDevice device;
	private MenuItem zoomMenuItem;

	private class GameListener extends MinesGameAdapter {
		@Override
		public void onBusted() {
			onGameLost();
		}

		@Override
		public void onDisarmed() {
			onGameWon();
		}
	}

	private int getBuildNumber() {
		try {
			PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
			return pi.versionCode;
		} catch (PackageManager.NameNotFoundException e) {
			LOGGER.e("Package name not found", e);
			return 0;
		}
	}

	@Override
	public void onCreate(final Bundle savedInstanceState) {
		debugLog("onCreate");
		super.onCreate(savedInstanceState);
		getWindow().setBackgroundDrawable(null);
		device = ((MinesApplication) getApplication()).getDevice();
		setFullScreenMode();
		handleIntentExtras();
		settings = new Settings(this);
		GameInfo gameInfo = new GameInfo(settings);
		device.setGameTitle(this, getTitle(), gameInfo.createTitle());
		notifier = new Notifier(this, gameInfo);
	}

	private void setFullScreenMode() {
		device.setFullScreen(this);
	}

	private void handleIntentExtras() {
		Intent intent = getIntent();
		skipCache = intent.getBooleanExtra(SKIP_CACHE, false);
		intent.removeExtra(SKIP_CACHE);
	}

	private void debugLog(final String msg) {
		LOGGER.d(msg);
	}

	@Override
	public Object onRetainNonConfigurationInstance() {
		debugLog("onRetainNonConfigurationInstance");
		return game;
	}

	@Override
	protected void onStart() {
		debugLog("onStart");
		notifier.clearRunningGame();
		super.onStart();
		setTheme(settings.getTheme());
		setContentView(R.layout.main);
		GameInfoView levelView = (GameInfoView) findViewById(R.id.level);
		if (levelView != null)
			levelView.setText(new GameInfo(settings).createTitle());
		CounterView timerView = (CounterView) findViewById(R.id.time);
		CounterView bombsView = (CounterView) findViewById(R.id.count);
		BoardView boardView = (BoardView) findViewById(R.id.board);
		game = restoreOrCreateGame((IGame) getLastNonConfigurationInstance());
		gameListener = new GameListener();
		game.addListener(gameListener);
		gameTimer = new GameTimer(game);
		timerController = new TimerController(game, timerView);
		bombsController = new BombsController(game, bombsView);
		boardController = new BoardController(game, boardView, settings);
		messagesController = new MessagesController(game, this, settings);
		showIntroOnAppStart();
	}

	private IGame restoreOrCreateGame(final IGame lastKnownGame) {
		if (lastKnownGame != null)
			return lastKnownGame;
		return readCachedGameOrCreateNew();
	}

	private IGame readCachedGameOrCreateNew() {
		IGame game = null;
		if (!skipCache)
			game = new ReadGameCommand(this, GAME_CACHE_FILE).execute();
		if (game == null)
			game = new GameCreator(settings).create();
		return game;
	}

	@Override
	protected void onResume() {
		debugLog("onResume");
		super.onResume();
		gameTimer.resume();
	}

	private void showIntroOnAppStart() {
		int buildNumber = getBuildNumber();
		if (buildNumber != settings.getLastUsedBuild()) {
			if (!game.isStarted())
				showDialog(DIALOG_INTRO);
			settings.setLastUsedBuild(buildNumber);
		}
	}

	@Override
	public boolean onCreateOptionsMenu(final Menu menu) {
		new MenuInflater(this).inflate(R.menu.menu, menu);
		zoomMenuItem = menu.findItem(R.id.zoom);
		adjustZoomIcon();
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onPrepareOptionsMenu(final Menu menu) {
		adjustZoomMenuVisibility(menu);
		return super.onPrepareOptionsMenu(menu);
	}

	private void adjustZoomMenuVisibility(final Menu menu) {
		final MenuItem zoom = menu.findItem(R.id.zoom);
		zoom.setVisible(!shouldHideZoomAction());
	}

	private boolean shouldHideZoomAction() {
		// hide zoom/fit items if board is too small for zooming
		return !boardController.isBoardFullyVisibleForFieldSize(settings.getZoomFieldSize()) || device.isGoogleTv();
	}

	@Override
	protected void onPause() {
		debugLog("onPause");
		gameTimer.pause();
		new SaveGameCommand(this, GAME_CACHE_FILE, game).execute();
		super.onPause();
	}

	@Override
	protected void onStop() {
		debugLog("onStop");
		gameTimer.dispose();
		boardController.dispose();
		bombsController.dispose();
		timerController.dispose();
		messagesController.dispose();
		game.removeListener(gameListener);
		if (settings.isNotify())
			notifier.notifyRunningGame(game);
		super.onStop();
	}

	@Override
	protected void onDestroy() {
		debugLog("onDestroy");
		super.onDestroy();
	}

	@Override
	protected void onRestart() {
		debugLog("onRestart");
		// make sure that cache is read on a restart
		skipCache = false;
		super.onRestart();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see android.app.Activity#onSearchRequested()
	 * 
	 * Prohibit searching when hitting search button. We handle this button
	 * ourselves.
	 */
	@Override
	public boolean onSearchRequested() {
		return false;
	}

	@Override
	public boolean onKeyUp(final int keyCode, final KeyEvent event) {
		if (keyCode == KeyEvent.KEYCODE_SEARCH) {
			if (boardController.isBoardFullyVisibleForFieldSize(settings.getZoomFieldSize())) {
				settings.toogleZoom();
				boardController.onZoomChange();
			}
			return true;
		}
		return super.onKeyUp(keyCode, event);
	}

	@Override
	public boolean onOptionsItemSelected(final MenuItem item) {
		switch (item.getItemId()) {
		case R.id.settings:
			startActivity(new Intent(this, MinesPreferences.class));
			break;
		case R.id.scores:
			startActivity(new Intent(this, HighScores.class));
			break;
		case R.id.new_game:
			showDialog(DIALOG_NEW_GAME);
			break;
		case R.id.zoom:
			settings.toogleZoom();
			adjustZoomIcon();
			boardController.onZoomChange();
			break;
		case R.id.help:
			showDialog(DIALOG_INTRO);
			break;
		}
		return true;
	}

	private void adjustZoomIcon() {
		if (zoomMenuItem == null) // menu not created yet
			return;
		if (!settings.isZoom())
			setZoomMenuIconAndText(R.drawable.ic_action_zoom_in, R.string.menu_zoom);
		else
			setZoomMenuIconAndText(R.drawable.ic_action_zoom_out, R.string.menu_fit);
	}

	private void setZoomMenuIconAndText(final int iconId, final int textId) {
		zoomMenuItem.setIcon(iconId);
		zoomMenuItem.setTitle(textId);
	}

	@Override
	public void onWindowFocusChanged(final boolean hasFocus) {
		if (hasFocus)
			gameTimer.resume();
		else
			gameTimer.pause();
		super.onWindowFocusChanged(hasFocus);
	}

	private void restartWithNewGame() {
		final Intent intent = new Intent(this, MineSweeper.class);
		intent.putExtra(SKIP_CACHE, true);
		notifier.disable();
		finish();
		startActivity(intent);
	}

	@Override
	protected Dialog onCreateDialog(final int id) {
		switch (id) {
		case DIALOG_NEW_GAME:
			return new NewGameDialogBuilder().create();
		case DIALOG_INTRO:
			AlertDialog dialog = new IntroDialogBuilder(this).create();

			/*
			 * we need to manually adjust the dialog width.
			 * 
			 * see:
			 * http://stackoverflow.com/questions/2306503/how-to-make-an-alert
			 * -dialog-fill-90-of-screen-size
			 */
			dialog.setOnShowListener(new OnShowListener() {

				@Override
				public void onShow(final DialogInterface dialogInterface) {
					WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
					Dialog dialogImpl = (Dialog) dialogInterface;
					lp.copyFrom(dialogImpl.getWindow().getAttributes());
					lp.width = WindowManager.LayoutParams.MATCH_PARENT;
					lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
					dialogImpl.getWindow().setAttributes(lp);
				}
			});
			return dialog;
		default:
			return super.onCreateDialog(id);
		}
	}

	private void onGameWon() {
        Intent intent = HighScores.withTime(this, game.getWatch().getTime());
        startActivityForResult(intent, HIGHSCORES_REQUEST);
	}

	@Override
	protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
		debugLog("onActivityResult");
		if (requestCode == HIGHSCORES_REQUEST)
			showDialog(DIALOG_NEW_GAME);
	}

	private void onGameLost() {
		showDialog(DIALOG_NEW_GAME);
	}

}
