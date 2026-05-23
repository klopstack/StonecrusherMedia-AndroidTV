package org.jellyfin.androidtv.ui.livetv;

import static org.koin.java.KoinJavaComponent.inject;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.RecyclerView;

import org.jellyfin.androidtv.R;
import org.jellyfin.androidtv.constant.CustomMessage;
import org.jellyfin.androidtv.data.repository.CustomMessageRepository;
import org.jellyfin.androidtv.databinding.LiveTvGuideBinding;
import org.jellyfin.androidtv.ui.AsyncImageView;
import org.jellyfin.androidtv.ui.FriendlyDateButton;
import org.jellyfin.androidtv.ui.GuideChannelHeader;
import org.jellyfin.androidtv.ui.LiveProgramDetailPopup;
import org.jellyfin.androidtv.ui.ObservableHorizontalScrollView;
import org.jellyfin.androidtv.ui.ProgramGridCell;
import org.jellyfin.androidtv.ui.playback.PlaybackLauncher;
import org.jellyfin.androidtv.util.CoroutineUtils;
import org.jellyfin.androidtv.util.DateTimeExtensionsKt;
import org.jellyfin.androidtv.util.ImageHelper;
import org.jellyfin.androidtv.util.InfoLayoutHelper;
import org.jellyfin.androidtv.util.PlaybackHelper;
import org.jellyfin.androidtv.util.TimeUtils;
import org.jellyfin.androidtv.util.Utils;
import org.jellyfin.androidtv.util.apiclient.EmptyResponse;
import org.jellyfin.androidtv.util.apiclient.Response;
import org.jellyfin.sdk.model.api.BaseItemDto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import kotlin.Lazy;
import kotlinx.coroutines.flow.MutableStateFlow;
import timber.log.Timber;

public class LiveTvGuideFragment extends Fragment implements LiveTvGuide, View.OnKeyListener {
    public static final int GUIDE_ROW_HEIGHT_DP = 55;
    public static final int GUIDE_ROW_WIDTH_PER_MIN_DP = 7;

    private TextView mDisplayDate;
    private TextView mTitle;
    private TextView mChannelStatus;
    private TextView mFilterStatus;
    private TextView mSummary;
    private AsyncImageView mImage;
    private LinearLayout mInfoRow;
    LinearLayout mTimeline;
    private HorizontalScrollView mTimelineScroller;
    private View mSpinner;
    private View mResetButton;
    LiveTvGuideGrid mGuideGrid;

    BaseItemDto mSelectedProgram;
    RelativeLayout mSelectedProgramView;

    private List<BaseItemDto> mAllChannels;
    private UUID mFirstFocusChannelId;
    private boolean focusAtEnd;
    GuideFilters mFilters = new GuideFilters();

    private LocalDateTime mCurrentGuideStart = LocalDateTime.now();
    LocalDateTime mCurrentGuideEnd;

    private Handler mHandler = new Handler();

    private final Lazy<CustomMessageRepository> customMessageRepository = inject(CustomMessageRepository.class);
    private final Lazy<PlaybackHelper> playbackHelper = inject(PlaybackHelper.class);
    private final Lazy<ImageHelper> imageHelper = inject(ImageHelper.class);
    private final Lazy<PlaybackLauncher> playbackLauncher = inject(PlaybackLauncher.class);
    private MutableStateFlow<Boolean> showOptions;
    private MutableStateFlow<Boolean> showFilterOptions;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        LiveTvGuideBinding binding = LiveTvGuideBinding.inflate(getLayoutInflater(), container, false);

        mDisplayDate = binding.displayDate;
        mTitle = binding.title;
        mSummary = binding.summary;
        mChannelStatus = binding.channelsStatus;
        mFilterStatus = binding.filterStatus;
        mChannelStatus.setTextColor(Color.GRAY);
        mFilterStatus.setTextColor(Color.GRAY);
        mInfoRow = binding.infoRow;
        mImage = binding.programImage;
        mTimeline = binding.timeline;
        mSpinner = binding.spinner;
        mSpinner.setVisibility(View.VISIBLE);

        showFilterOptions = LiveTvGuideFragmentHelperKt.addSettingsFilters(this, binding);
        View mFilterButton = binding.filterButton;
        mFilterButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showFilterOptions.setValue(true);
            }
        });
        mFilterButton.setContentDescription(getString(R.string.lbl_filters));

        showOptions = LiveTvGuideFragmentHelperKt.addSettingsOptions(this, binding);
        View mOptionsButton = binding.optionsButton;
        mOptionsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showOptions.setValue(true);
            }
        });
        mOptionsButton.setContentDescription(getString(R.string.lbl_other_options));

        View mDateButton = binding.dateButton;
        mDateButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDatePicker();
            }
        });
        mDateButton.setContentDescription(getString(R.string.lbl_select_date));

        mResetButton = binding.resetButton;
        mResetButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pageGuideTo(LocalDateTime.now());
            }
        });

        mTimelineScroller = binding.timelineHScroller;
        mTimelineScroller.setFocusable(false);
        mTimelineScroller.setFocusableInTouchMode(false);
        mTimeline.setFocusable(false);
        mTimeline.setFocusableInTouchMode(false);

        mGuideGrid = new LiveTvGuideGrid(
                this,
                this,
                mFilters,
                binding.channelList,
                binding.programList,
                binding.programHScroller,
                mTimelineScroller
        );
        mGuideGrid.initialize();

        // Register to receive message from popup
        CoroutineUtils.readCustomMessagesOnLifecycle(getLifecycle(), customMessageRepository.getValue(), message -> {
            if (message.equals(CustomMessage.ActionComplete.INSTANCE)) dismissProgramOptions();
            return null;
        });

        return binding.getRoot();
    }


    private void load() {
        mCurrentGuideStart = LocalDateTime.now();
        mCurrentGuideStart = GuideTimeWindow.roundGuideStart(mCurrentGuideStart);
        mCurrentGuideEnd = GuideTimeWindow.initialDisplayEnd(mCurrentGuideStart, mFilters.any());
        LiveTvGuideFragmentHelperKt.buildInitialTimeLine(this, mCurrentGuideStart);
        TvManager.loadAllChannels(this, ndx -> {
            mAllChannels = TvManager.getAllChannels();
            if (!mAllChannels.isEmpty()) {
                boolean hydrated = mGuideGrid.hydrateFromDisk();
                mGuideGrid.prepareGuideWindow(mCurrentGuideStart, mCurrentGuideEnd, mAllChannels.size(), hydrated);
                int scrollIndex = computeInitialScrollIndex(ndx);
                mGuideGrid.scrollToChannel(scrollIndex);
                mGuideGrid.requestInitialPrograms(ndx);
                updateChannelStatus();
                requestInitialFocus();
                mSpinner.setVisibility(View.GONE);
            } else {
                mSpinner.setVisibility(View.GONE);
            }
            return null;
        });
    }

    private int computeInitialScrollIndex(int lastChannelIndex) {
        return Math.max(0, lastChannelIndex - LiveTvGuideGrid.CHANNEL_LOAD_RADIUS);
    }

    private void requestInitialFocus() {
        if (mFirstFocusChannelId == null) return;
        UUID focusId = mFirstFocusChannelId;
        mFirstFocusChannelId = null;
        boolean focusEnd = focusAtEnd;
        focusAtEnd = false;
        requireView().post(() -> {
            if (!isAdded()) return;
            mGuideGrid.requestFocusForChannel(focusId, focusEnd);
        });
    }

    void updateChannelStatus() {
        int total = mAllChannels != null ? mAllChannels.size() : 0;
        mChannelStatus.setText(total + " channels");
        mFilterStatus.setText(mFilters.toString() + " for " + LiveTvGuideFragmentHelperKt.getDisplayHours(this) + " hours");
        mFilterStatus.setTextColor(mFilters.any() ? Color.WHITE : Color.GRAY);
        mResetButton.setVisibility(mCurrentGuideStart.isAfter(LocalDateTime.now()) ? View.VISIBLE : View.GONE);
    }

    @Override
    public void scrollToChannel(int index) {
        if (mGuideGrid != null) {
            mGuideGrid.scrollToChannel(index);
        }
    }

    public void refreshFavorite(UUID channelId) {
        if (mGuideGrid != null) {
            mGuideGrid.refreshFavorite(channelId);
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        mFilters.load();
        doLoad();
    }

    protected void doLoad() {
        if (TvManager.shouldForceReload() || mCurrentGuideStart.plusMinutes(30).isBefore(LocalDateTime.now())
                || mAllChannels == null || mAllChannels.isEmpty()) {
            load();

            mFirstFocusChannelId = TvManager.getLastLiveTvChannel();
        }
    }

    @Override
    public void onPause() {
        super.onPause();

        if (mDetailPopup != null) {
            mDetailPopup.dismiss();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (mCurrentGuideStart.isAfter(LocalDateTime.now())) {
            TvManager.forceReload(); //we paged ahead - force a re-load if we come back in
        }
    }

    @Override
    public boolean onKey(View v, int keyCode, KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_UP) return onKeyUp(keyCode, event);
        else if (event.getAction() == KeyEvent.ACTION_DOWN && event.isLongPress()) return onKeyLongPress(keyCode);
        else if (event.getAction() == KeyEvent.ACTION_DOWN) return onKeyDown(keyCode, event);
        return false;
    }

    private boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode){
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                event.startTracking();
                return true;
        }
        return false;
    }

    private boolean onKeyLongPress(int keyCode) {
        switch (keyCode){
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                if (mSelectedProgramView instanceof ProgramGridCell)
                    showProgramOptions();
                else if(mSelectedProgramView instanceof GuideChannelHeader)
                    LiveTvGuideFragmentHelperKt.toggleFavorite(this);
                return true;
        }
        return false;
    }

    private boolean onKeyUp(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MENU:
                // bring up filter selection
                showFilterOptions.setValue(true);
                break;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                if ((event.getFlags() & KeyEvent.FLAG_CANCELED_LONG_PRESS) == 0) {
                    if (mSelectedProgramView instanceof ProgramGridCell) {
                        if (mSelectedProgram.getStartDate().isBefore(LocalDateTime.now()))
                            playbackHelper.getValue().retrieveAndPlay(mSelectedProgram.getChannelId(), false, requireContext());
                        else
                            showProgramOptions();
                        return true;
                    } else if (mSelectedProgramView instanceof GuideChannelHeader) {
                        // Tuning directly to a channel
                        GuideChannelHeader channelHeader = (GuideChannelHeader) mSelectedProgramView;
                        playbackHelper.getValue().getItemsToPlay(requireContext(), channelHeader.getChannel(), false, false, new Response<List<BaseItemDto>>(getLifecycle()) {
                            @Override
                            public void onResponse(List<BaseItemDto> response) {
                                if (!isActive()) return;
                                playbackLauncher.getValue().launch(requireContext(), response);
                            }
                        });
                    }
                }
                return false;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if ((mDetailPopup == null || !mDetailPopup.isShowing())
                        && mSelectedProgram != null
                        && mSelectedProgram.getChannelId() != null) {
                    // tune to the current channel
                    playbackHelper.getValue().retrieveAndPlay(mSelectedProgram.getChannelId(), false, requireContext());
                    return true;
                }

            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (requireActivity().getCurrentFocus() instanceof ProgramGridCell
                        && mSelectedProgramView != null
                        && ((ProgramGridCell)mSelectedProgramView).isLast()) {
                    if (mCurrentGuideEnd.isBefore(GuideTimeWindow.maxHorizon())) {
                        mGuideGrid.extendHorizontally();
                    } else {
                        requestGuidePage(mCurrentGuideEnd);
                    }
                }
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (requireActivity().getCurrentFocus() instanceof ProgramGridCell
                        && mSelectedProgramView != null
                        && ((ProgramGridCell)mSelectedProgramView).isFirst()
                        && mSelectedProgram.getStartDate().isAfter(LocalDateTime.now())) {
                    focusAtEnd = true;
                    requestGuidePage(mCurrentGuideStart.minusHours(LiveTvGuideFragmentHelperKt.getDisplayHours(this)));
                }
                break;
        }

        return false;
    }

    View.OnClickListener datePickedListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            pageGuideTo(((FriendlyDateButton)v).getDate());
            dateDialog.dismiss();
        }
    };

    AlertDialog dateDialog;

    private void showDatePicker() {
        FrameLayout scrollPane = (FrameLayout) getLayoutInflater().inflate(R.layout.horizontal_scroll_pane, null);
        LinearLayout scrollItems = scrollPane.findViewById(R.id.scrollItems);
        for (long increment = 0; increment < 15; increment++) {
            scrollItems.addView(new FriendlyDateButton(requireContext(),  LocalDateTime.now().plusDays(increment), datePickedListener));
        }

        dateDialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.lbl_select_date)
                .setView(scrollPane)
                .setNegativeButton(R.string.btn_cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {

                    }
                })
                .show();

    }

    private void requestGuidePage(final LocalDateTime startTime) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.lbl_load_guide_data)
                .setMessage(startTime.isAfter(mCurrentGuideStart) ? getString(R.string.msg_live_tv_next, LiveTvGuideFragmentHelperKt.getDisplayHours(this)) : getString(R.string.msg_live_tv_prev, LiveTvGuideFragmentHelperKt.getDisplayHours(this)))
                .setPositiveButton(R.string.lbl_yes, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        pageGuideTo(startTime);
                    }
                })
                .setNegativeButton(R.string.lbl_no, null)
                .show();
    }

    private void pageGuideTo(LocalDateTime startTime) {
        if (startTime.isBefore(LocalDateTime.now())) startTime = LocalDateTime.now();
        Timber.i("page to %s", startTime);
        TvManager.forceReload();
        if (mSelectedProgram != null) {
            mFirstFocusChannelId = mSelectedProgram.getChannelId();
        }
        mCurrentGuideStart = GuideTimeWindow.roundGuideStart(startTime);
        mCurrentGuideEnd = GuideTimeWindow.initialDisplayEnd(mCurrentGuideStart, mFilters.any());
        LiveTvGuideFragmentHelperKt.buildInitialTimeLine(this, mCurrentGuideStart);
        if (mGuideGrid != null && mAllChannels != null && !mAllChannels.isEmpty()) {
            mGuideGrid.resetGuideWindow(mCurrentGuideStart, mCurrentGuideEnd);
            int centerIndex = mFirstFocusChannelId != null
                    ? TvManager.getAllChannelsIndex(mFirstFocusChannelId)
                    : 0;
            if (centerIndex < 0) centerIndex = 0;
            mGuideGrid.scrollToChannel(computeInitialScrollIndex(centerIndex));
            mGuideGrid.requestInitialPrograms(centerIndex);
            updateChannelStatus();
            requestInitialFocus();
        }
    }

    private LiveProgramDetailPopup mDetailPopup;

    public void dismissProgramOptions() {
        if (mDetailPopup != null) {
            mDetailPopup.dismiss();
        }
    }

    public void showProgramOptions() {
        if (mSelectedProgram == null) return;
        if (mDetailPopup == null) {
            mDetailPopup = new LiveProgramDetailPopup(requireActivity(), this, this, mSummary.getWidth()+20, new EmptyResponse(getLifecycle()) {
                @Override
                public void onResponse() {
                    if (!isActive()) return;
                    playbackHelper.getValue().retrieveAndPlay(mSelectedProgram.getChannelId(), false, requireContext());
                }
            });
        }

        mDetailPopup.setContent(mSelectedProgram, ((ProgramGridCell)mSelectedProgramView));
        mDetailPopup.show(mImage, mTitle.getLeft(), mTitle.getTop() - 10);
    }

    void fillTimeLine(LocalDateTime start, int hours) {
        mCurrentGuideStart = GuideTimeWindow.roundGuideStart(start);
        mDisplayDate.setText(TimeUtils.getFriendlyDate(requireContext(), mCurrentGuideStart));
        mCurrentGuideEnd = mCurrentGuideStart.plusHours(hours);
        LiveTvGuideFragmentHelperKt.fillLinearTimeLine(this, mCurrentGuideStart, mCurrentGuideEnd);
    }

    @Override
    public LocalDateTime getCurrentLocalStartDate() { return mCurrentGuideStart; }

    @Override
    public LocalDateTime getCurrentLocalEndDate() { return mCurrentGuideEnd; }

    private Runnable detailUpdateTask = new Runnable() {
        @Override
        public void run() {
            if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) return;
            LiveTvGuideFragmentHelperKt.refreshSelectedProgram(LiveTvGuideFragment.this);
        }
    };

    void detailUpdateInternal() {
        if (mSelectedProgram == null) return;

        mTitle.setText(mSelectedProgram.getName());
        mSummary.setText(mSelectedProgram.getOverview());

        //info row
        InfoLayoutHelper.addInfoRow(requireContext(), mSelectedProgram, mInfoRow, false);

        mDisplayDate.setText(TimeUtils.getFriendlyDate(requireContext(), mSelectedProgram.getStartDate()));
        String url = imageHelper.getValue().getPrimaryImageUrl(mSelectedProgram, null, ImageHelper.MAX_PRIMARY_IMAGE_HEIGHT);
        mImage.load(url, null, ContextCompat.getDrawable(requireContext(), R.drawable.blank10x10), 0, 0);

        if (mDetailPopup != null && mDetailPopup.isShowing() && mSelectedProgramView != null) {
            mDetailPopup.setContent(mSelectedProgram, ((ProgramGridCell) mSelectedProgramView));
        }
    }

    public void setSelectedProgram(RelativeLayout programView) {
        mSelectedProgramView = programView;
        if (mSelectedProgramView instanceof ProgramGridCell) {
            mSelectedProgram = ((ProgramGridCell)mSelectedProgramView).getProgram();
            if (mGuideGrid != null) {
                mGuideGrid.onProgramCellFocused(mSelectedProgram);
            }
            mHandler.removeCallbacks(detailUpdateTask);
            mHandler.postDelayed(detailUpdateTask, 500);
        } else if (mSelectedProgramView instanceof GuideChannelHeader) {
            LinearLayout programRow = mGuideGrid.findProgramRowForChannelHeader((GuideChannelHeader) mSelectedProgramView);
            if (programRow == null) return;
            BaseItemDto current = mGuideGrid.findCurrentProgramInRow(programRow);
            if (current != null) {
                mSelectedProgram = current;
                mHandler.removeCallbacks(detailUpdateTask);
                mHandler.postDelayed(detailUpdateTask, 500);
            }
        }
    }

    @Override
    public void redirectChannelHeaderFocus(GuideChannelHeader header) {
        if (mGuideGrid != null) {
            mGuideGrid.redirectChannelHeaderFocus(header);
        }
    }

    @Override
    public void onGuideDisplayEndExtended(LocalDateTime newEnd) {
        // display end updated via extendTimeLineTo
    }

    @Override
    public void extendTimeLineTo(LocalDateTime newEnd) {
        LiveTvGuideFragmentHelperKt.extendTimeLineTo(this, newEnd);
    }

    @Override
    public View findVerticalProgramFocusTarget(GuideChannelHeader header, int direction) {
        if (mGuideGrid != null) {
            return mGuideGrid.findVerticalProgramFocusTarget(header, direction);
        }
        return null;
    }
}
