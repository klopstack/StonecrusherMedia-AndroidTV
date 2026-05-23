package org.jellyfin.androidtv.ui;

import static org.koin.java.KoinJavaComponent.get;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import org.jellyfin.androidtv.R;
import org.jellyfin.androidtv.preference.LiveTvPreferences;
import org.jellyfin.androidtv.ui.livetv.GuideCellDisplayOptions;
import org.jellyfin.androidtv.ui.livetv.LiveTvGuide;
import org.jellyfin.androidtv.util.DateTimeExtensionsKt;
import org.jellyfin.androidtv.util.Utils;
import org.jellyfin.androidtv.util.sdk.BaseItemExtensionsKt;
import org.jellyfin.sdk.model.api.BaseItemDto;

import java.time.LocalDateTime;

public class ProgramGridCell extends RelativeLayout implements RecordingIndicatorView {

    private LiveTvGuide mActivity;
    private TextView mProgramName;
    private LinearLayout mInfoRow;
    private BaseItemDto mProgram;
    private ImageView mRecIndicator;
    private int mBackgroundColor = 0;
    private boolean isLast;
    private boolean isFirst;
    private GuideCellDisplayOptions mDisplayOptions;

    public ProgramGridCell(Context context, LiveTvGuide activity, BaseItemDto program, boolean keyListen) {
        this(context, activity, program, keyListen, null);
    }

    public ProgramGridCell(Context context, LiveTvGuide activity, BaseItemDto program, boolean keyListen, GuideCellDisplayOptions displayOptions) {
        super(context);
        mDisplayOptions = displayOptions;
        initComponent((Activity) context, activity, program, keyListen);
    }

    private GuideCellDisplayOptions getDisplayOptions() {
        if (mDisplayOptions == null) {
            mDisplayOptions = GuideCellDisplayOptions.Companion.from(get(LiveTvPreferences.class));
        }
        return mDisplayOptions;
    }

    private void initComponent(Activity context, LiveTvGuide activity, BaseItemDto program, boolean keyListen) {
        mActivity = activity;

        LayoutInflater inflater = LayoutInflater.from(context);
        View v = inflater.inflate(R.layout.program_grid_cell, this, false);
        this.addView(v);

        setFocusable(true);

        mProgramName = findViewById(R.id.programName);
        mInfoRow = findViewById(R.id.infoRow);
        mProgramName.setText(program.getName());
        mProgram = program;
        mRecIndicator = findViewById(R.id.recIndicator);

        setCellBackground();

        if (program.getStartDate() != null && program.getEndDate() != null) {
            LocalDateTime localStart = program.getStartDate();
            if (localStart.plusMinutes(1).isBefore(activity.getCurrentLocalStartDate())) {
                mProgramName.setText("<< "+mProgramName.getText());
                TextView time = new TextView(context);
                time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
                time.setTextSize(12);
                time.setText(DateTimeExtensionsKt.getTimeFormatter(getContext()).format(program.getStartDate()));
                mInfoRow.addView(time);
            }
        }

        GuideCellDisplayOptions displayOptions = getDisplayOptions();

        if (displayOptions.getShowNewIndicator() && BaseItemExtensionsKt.isNew(program) && (!displayOptions.getShowPremiereIndicator() || !Utils.isTrue(program.isPremiere()))) {
            addBlockText(context.getString(R.string.lbl_new), 10, Color.GRAY, R.drawable.dark_green_gradient);
        }

        if (displayOptions.getShowPremiereIndicator() && Utils.isTrue(program.isPremiere())) {
            addBlockText(context.getString(R.string.lbl_premiere), 10, Color.GRAY, R.drawable.dark_green_gradient);
        }

        if (displayOptions.getShowRepeatIndicator() && Utils.isTrue(program.isRepeat())) {
            addBlockText(context.getString(R.string.lbl_repeat), 10, Color.GRAY, androidx.leanback.R.color.lb_default_brand_color);
        }

        if (program.getOfficialRating() != null && !program.getOfficialRating().equals("0")) {
            addBlockText(program.getOfficialRating(), 10, Color.BLACK, R.drawable.block_text_bg);
        }

        if (displayOptions.getShowHdIndicator() && Utils.isTrue(program.isHd())) {
            addBlockText("HD", 10, Color.BLACK, R.drawable.block_text_bg);
        }

        if (program.getSeriesTimerId() != null) {
            mRecIndicator.setImageResource(program.getTimerId() != null ? R.drawable.ic_record_series_red : R.drawable.ic_record_series);
        } else if (program.getTimerId() != null) {
            mRecIndicator.setImageResource(R.drawable.ic_record_red);
        }


        if (keyListen) {
            setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    mActivity.showProgramOptions();
                }
            });
        }

    }

    private void addBlockText(String text, int size, int textColor, int backgroundRes) {
        TextView view = new TextView(getContext());
        view.setTextSize(size);
        view.setTextColor(textColor);
        view.setText(" " + text + " ");
        view.setBackgroundResource(backgroundRes);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        params.setMargins(0, Utils.convertDpToPixel(getContext(), -2), 0, 0);
        view.setLayoutParams(params);
        mInfoRow.addView(view);
    }

    public void setCellBackground() {
        GuideCellDisplayOptions displayOptions = getDisplayOptions();

        if (displayOptions.getColorCodeGuide()) {
            if (Utils.isTrue(mProgram.isMovie())) {
                mBackgroundColor = getResources().getColor(R.color.guide_movie_bg);
            } else if (Utils.isTrue(mProgram.isNews())) {
                mBackgroundColor = getResources().getColor(R.color.guide_news_bg);
            } else if (Utils.isTrue(mProgram.isSports())) {
                mBackgroundColor = getResources().getColor(R.color.guide_sports_bg);
            } else if (Utils.isTrue(mProgram.isKids())) {
                mBackgroundColor = getResources().getColor(R.color.guide_kids_bg);
            }

            setBackgroundColor(mBackgroundColor);
        }
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);

        if (gainFocus) {
            setBackgroundColor(Utils.getThemeColor(getContext(), android.R.attr.colorAccent));

            mActivity.setSelectedProgram(this);
        } else {
            setBackgroundColor(mBackgroundColor);
        }
    }

    public BaseItemDto getProgram() { return mProgram; }

    public void setLast() { isLast = true; }
    public boolean isLast() { return isLast; }
    public void setFirst() { isFirst = true; }
    public boolean isFirst() { return isFirst; }

    public void setRecTimer(String id) {
        if (mProgram == null) return;
        mProgram = LiveProgramDetailPopupHelperKt.copyWithTimerId(mProgram, id);
        mRecIndicator.setImageResource(id != null ? (mProgram.getSeriesTimerId() != null ? R.drawable.ic_record_series_red : R.drawable.ic_record_red) : mProgram.getSeriesTimerId() != null ? R.drawable.ic_record_series : R.drawable.blank10x10);
    }
    public void setRecSeriesTimer(String id) {
        if (mProgram == null) return;
        mProgram = LiveProgramDetailPopupHelperKt.copyWithSeriesTimerId(mProgram, id);
        mRecIndicator.setImageResource(id != null ? R.drawable.ic_record_series_red : R.drawable.blank10x10);
    }
}
