package com.lh.danmakulibrary;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Created by liuhui on 2016/9/8.
 * 弹幕控件
 */
@SuppressWarnings({"FieldCanBeLocal", "unused"})
public class DanmakuView extends View {

    private static final long REFRESH_TIME = 100;
    private static final int MAX_TRACK_HEIGHT = 20;

    private int mScreenHeight;
    private int mScreenWidth;

    private float mDensity;
    private float mScaleTextRatio = 0.8f; //文本缩放倍率
    private float mSpeedRatio = 1.2f; //速度倍率
    private int mMaxDanmakuTrackCount = 20; //显示弹幕的轨道数(最大)
    private long mCenterDanmakuShowTime = 5000; //居中弹幕的显示时长
    private long mScrollDanmakuShowTime = 9000; //滚动弹幕显示时长

    private float mStrokeWidth = 0.8f; //文本描边宽度
    private float mPeerTrackHeight; //每条轨道的高度
    private float mTrackMargin; //每条轨道的间距
    private int mMaxDanmakuCount = 50; //最大同时显示的弹幕数量
    private int mCurrentDanmakuCount = 0; //当前同屏的弹幕数量

    private boolean mShowDebugInfo; //是否显示Debug信息

    private boolean mShowDanmaku; //是否显示弹幕

    private TextPaint mDebugTextPaint;
    private int mDebugTextSize;
    private int mFps;
    private int mFrame;
    private long mDebugStartTime = 0;
    private int mNewCount = 0;

    private Timer mTimer;
    private DanmakuTimerTask mTask;

    private ArrayList<Danmaku> mDanmakus; //弹幕数据源

    private LinkedList<DanmakuWrapped> mScrapDanmakus = new LinkedList<>(); //弹幕回收池
    private ArrayList<DanmakuTrack> mDanmakuTracks; //弹幕轨道数组

    private DanmakuState mDanmakuState;

    private long mCurrentTime = -1;
    private long mStartTime = -1;

    private int mLastAddDanmakuIndex; //最后出现的弹幕的索引

    public DanmakuView(Context context) {
        super(context);
        init();
    }


    public DanmakuView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public DanmakuView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    //初始化
    private void init() {
        mShowDebugInfo = false;

        mShowDanmaku = true;
        mDanmakuState = DanmakuState.IDLE;
        mDensity = getResources().getDisplayMetrics().density;

        mDebugTextPaint = new TextPaint();
        mDebugTextSize = dip2px(15);
        mDebugTextPaint.setTextSize(mDebugTextSize);
        mDebugTextPaint.setColor(Color.WHITE);

        mDanmakuTracks = new ArrayList<>();
    }

    public void setShowDebugInfo(boolean showDebugInfo) {
        this.mShowDebugInfo = showDebugInfo;
    }

    public void setDanmakuSource(ArrayList<Danmaku> danmakuSource) {
        this.mDanmakus = danmakuSource;
        prepareDanmakuTrack();
    }

    public void start() {
        if (mDanmakuState == DanmakuState.Prepared) {
            mDanmakuState = DanmakuState.Playing;
            mTimer = new Timer();
            mTask = new DanmakuTimerTask();
            mTimer.scheduleAtFixedRate(mTask, 0, REFRESH_TIME);
            postInvalidate();
        }
    }

    public void pause() {
        if (mDanmakuState == DanmakuState.Playing && mTimer != null && mTask != null) {
            mDanmakuState = DanmakuState.Pause;
            mTimer.cancel();
            mTask.cancel();
            mTimer = null;
            mTask = null;
        }
    }

    public void resume() {
        if (mDanmakuState == DanmakuState.Pause) {
            mDanmakuState = DanmakuState.Playing;
            mTimer = new Timer();
            mTask = new DanmakuTimerTask();
            mTimer.scheduleAtFixedRate(mTask, 0, REFRESH_TIME);
            postInvalidate();
        }
    }

    public void seekTo(long time) {
        if (mDanmakuState != DanmakuState.IDLE) {
            mCurrentTime = time;
            for (int i = 0; i < mDanmakus.size(); i++) {
                Danmaku danmaku = mDanmakus.get(i);
                if (danmaku.getTime() >= time) {
                    mLastAddDanmakuIndex = i;
                    break;
                }
            }
            clearAllDanamku();
            postInvalidate();
        }
    }

    public void stop() {
        mDanmakuState = DanmakuState.IDLE;
        if (mTimer != null) {
            mTimer.cancel();
        }
        if (mTask != null) {
            mTask.cancel();
        }
        for (DanmakuTrack track : mDanmakuTracks) {
            track.mDanmakus.clear();
            track.haveCenterDanmaku = false;
            track.lastScrollDanmaku = null;
        }
        mDanmakuTracks.clear();
        mScrapDanmakus.clear();
        mTimer = null;
        mTask = null;
        mCurrentTime = -1;
        mStartTime = -1;
        mNewCount = 0;
        mLastAddDanmakuIndex = 0;
        postInvalidate();
    }

    public void show() {
        if (!mShowDanmaku) {
            mShowDanmaku = true;
            postInvalidate();
        }
    }

    public void hide() {
        if (mShowDanmaku) {
            mShowDanmaku = false;
            postInvalidate();
        }
    }

    public boolean isShow() {
        return mShowDanmaku;
    }

    public boolean isPrepared() {
        return mDanmakuState != DanmakuState.IDLE;
    }

    public boolean isPaused() {
        return mDanmakuState == DanmakuState.Pause;
    }

    public long getCurrentTime() {
        return mCurrentTime;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        mScreenHeight = getMeasuredHeight();
        mScreenWidth = getMeasuredWidth();
    }

    //准备弹幕轨道
    private void prepareDanmakuTrack() {
        if (mDanmakuState == DanmakuState.IDLE) {
            mDanmakuState = DanmakuState.Prepared;
            TextPaint measureTextPaint = new TextPaint();
            measureTextPaint.setAntiAlias(true);
            measureTextPaint.setFakeBoldText(true);
            measureTextPaint.setTextSize(dip2px(MAX_TRACK_HEIGHT) * mScaleTextRatio);
            TextPaint.FontMetrics fontMetrics = measureTextPaint.getFontMetrics();
            mPeerTrackHeight = fontMetrics.bottom - fontMetrics.ascent;
            float trackTotalHeight = 0;
            int currentTrackCount = 0;
            while (trackTotalHeight + mPeerTrackHeight < mScreenHeight && currentTrackCount < mMaxDanmakuTrackCount) {
                trackTotalHeight += mPeerTrackHeight;
                currentTrackCount++;
            }
            mTrackMargin = (mScreenHeight - trackTotalHeight) / (currentTrackCount * 2);
            float currentY = mTrackMargin;
            for (int i = 0; i < currentTrackCount; i++) {
                DanmakuTrack track = new DanmakuTrack();
                track.y = currentY;
                mDanmakuTracks.add(track);
                currentY += (mPeerTrackHeight + 2 * mTrackMargin);
            }
        }
    }

    //添加一条弹幕数据
    public void addDanamku(Danmaku danmaku) {
        if (mCurrentDanmakuCount >= mMaxDanmakuCount || mDanmakuState == DanmakuState.IDLE) {
            return;
        }
        if (danmaku.getType() == 1) { //滚动弹幕
            int trackIndex = findAvaliableScrollTrack();
            if (trackIndex >= 0 && trackIndex < mDanmakuTracks.size()) {
                DanmakuWrapped danmakuWrapped = wrapDanmaku(danmaku);
                danmakuWrapped.x = getWidth();
                addScrollDanmaku(danmakuWrapped, trackIndex);
            }
        } else if (danmaku.getType() == 5) { //顶部弹幕
            int trackIndex = findAvaliableTopCenterTrack();
            if (trackIndex >= 0 && trackIndex < mDanmakuTracks.size()) {
                DanmakuWrapped danmakuWrapped = wrapDanmaku(danmaku);
                danmakuWrapped.x = (getWidth() - danmakuWrapped.getWidth()) / 2;
                danmakuWrapped.showTime = System.currentTimeMillis();
                addCenterDanmaku(danmakuWrapped, trackIndex);
            }
        } else if (danmaku.getType() == 4) { //底部弹幕
            int trackIndex = findAvaliableBottomCenterTrack();
            if (trackIndex >= 0 && trackIndex < mDanmakuTracks.size()) {
                DanmakuWrapped danmakuWrapped = wrapDanmaku(danmaku);
                danmakuWrapped.x = (getWidth() - danmakuWrapped.getWidth()) / 2;
                danmakuWrapped.showTime = System.currentTimeMillis();
                addCenterDanmaku(danmakuWrapped, trackIndex);
            }
        }
    }

    private void addCenterDanmaku(DanmakuWrapped danmakuWrapped, int trackIndex) {
        mCurrentDanmakuCount++;
        mDanmakuTracks.get(trackIndex).mWaitToAddDanamku = danmakuWrapped;
        mDanmakuTracks.get(trackIndex).haveCenterDanmaku = true;
    }

    private void addScrollDanmaku(DanmakuWrapped danmakuWrapped, int trackIndex) {
        mCurrentDanmakuCount++;
        mDanmakuTracks.get(trackIndex).mWaitToAddDanamku = danmakuWrapped;
        mDanmakuTracks.get(trackIndex).lastScrollDanmaku = danmakuWrapped;
    }

    private void clearAllDanamku() {
        for (int i = 0; i < mDanmakuTracks.size(); i++) {
            DanmakuTrack track = mDanmakuTracks.get(i);
            mScrapDanmakus.addAll(track.mDanmakus);
            track.haveCenterDanmaku = false;
            if (track.mWaitToAddDanamku != null) {
                mScrapDanmakus.add(track.mWaitToAddDanamku);
                track.mWaitToAddDanamku = null;
            }
            track.mDanmakus.clear();
            mCurrentDanmakuCount = 0;
        }
    }

    //将添加的弹幕进行处理
    private DanmakuWrapped wrapDanmaku(Danmaku danmaku) {
        DanmakuWrapped danmakuWrapped;
        if (mScrapDanmakus.size() != 0) {
            danmakuWrapped = mScrapDanmakus.getFirst();
            mScrapDanmakus.removeFirst();
        } else {
            mNewCount++;
            danmakuWrapped = new DanmakuWrapped();
        }
        danmakuWrapped.wrapDanmaku(danmaku);
        return danmakuWrapped;
    }

    //寻找一条可用的滚动弹幕轨道
    private int findAvaliableScrollTrack() {
        for (int i = 0; i < mDanmakuTracks.size(); i++) {
            DanmakuTrack track = mDanmakuTracks.get(i);
            if ((track.mDanmakus.size() == 0 && track.mWaitToAddDanamku == null && !track.haveCenterDanmaku) || (track.mDanmakus.size() == 1 && track.mWaitToAddDanamku == null && track.haveCenterDanmaku)) {
                return i;
            } else {
                DanmakuWrapped lastScrollDanmaku = track.lastScrollDanmaku;
                if (lastScrollDanmaku != null && track.mWaitToAddDanamku == null && lastScrollDanmaku.x + lastScrollDanmaku.getWidth() < getWidth()) {
                    return i;
                }
            }
        }
        return -1;
    }

    //寻找一条可用的顶部居中弹幕轨道
    private int findAvaliableTopCenterTrack() {
        for (int i = 0; i < mDanmakuTracks.size(); i++) {
            DanmakuTrack track = mDanmakuTracks.get(i);
            if (!track.haveCenterDanmaku && track.mWaitToAddDanamku == null) {
                return i;
            }
        }
        return -1;
    }

    //寻找一条可用的底部居中弹幕轨道
    private int findAvaliableBottomCenterTrack() {
        for (int i = mDanmakuTracks.size() - 1; i >= 0; i--) {
            DanmakuTrack track = mDanmakuTracks.get(i);
            if (!track.haveCenterDanmaku && track.mWaitToAddDanamku == null) {
                return i;
            }
        }
        return -1;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (int i = 0; i < mDanmakuTracks.size(); i++) {
            DanmakuTrack track = mDanmakuTracks.get(i);
            if (track.mWaitToAddDanamku != null) {
                track.mDanmakus.add(track.mWaitToAddDanamku);
                track.mWaitToAddDanamku = null;
            }
            if (mShowDanmaku) {
                for (DanmakuWrapped danmakuWrapped : mDanmakuTracks.get(i).mDanmakus) {
                    danmakuWrapped.draw(canvas, track.y);
                }
            }
        }
        if (mShowDebugInfo) {
            drawDebugInfo(canvas);
        }
        refreshDanmakuView();
    }

    private void drawDebugInfo(Canvas canvas) {
        mFrame++;
        final long nowTime = System.currentTimeMillis();
        final long deltaTime = nowTime - mDebugStartTime;
        if (deltaTime > 1000) {
            mFps = (int) (mFrame * 1000 / deltaTime);
            mFrame = 0;
            mDebugStartTime = nowTime;
        }
        Paint.FontMetrics fontMetrics = mDebugTextPaint.getFontMetrics();
        float textHeight = (float) Math.ceil(fontMetrics.descent - fontMetrics.ascent);
        canvas.drawText(String.format(Locale.getDefault(), "fps:%d count:%d/%d/%d time:%.1fs", mFps, mCurrentDanmakuCount, mScrapDanmakus.size(), mNewCount, mCurrentTime / 1000f), 0, mScreenHeight - textHeight - fontMetrics.bottom, mDebugTextPaint);
        canvas.drawText(String.format(Locale.getDefault(), "trackCount:%d/%d trackHeight:%.1f trackMargin:%.1f screenSize:%d/%d", mDanmakuTracks.size(), mMaxDanmakuTrackCount, mPeerTrackHeight, mTrackMargin, mScreenWidth, mScreenHeight), 0, mScreenHeight - fontMetrics.bottom, mDebugTextPaint);
    }

    private void refreshDanmakuView() {
        for (int i = 0; i < mDanmakuTracks.size(); i++) {
            refreshSingleTrackDanmaku(mDanmakuTracks.get(i));
        }
        if (mDanmakuState == DanmakuState.Playing) {
            postInvalidate();
        }
    }

    private void refreshSingleTrackDanmaku(DanmakuTrack track) {
        Iterator<DanmakuWrapped> iterator = track.mDanmakus.iterator();
        while (iterator.hasNext()) {
            DanmakuWrapped danmaku = iterator.next();
            if (danmaku.danmaku.getType() == 1) {
                if (danmaku.x + danmaku.getWidth() < 0) {
                    iterator.remove();
                    mScrapDanmakus.add(danmaku);
                    mCurrentDanmakuCount--;
                } else {
                    danmaku.x -= danmaku.speed;
                }
            } else if (danmaku.getType() == 4 || danmaku.getType() == 5) {
                if (System.currentTimeMillis() - danmaku.showTime > mCenterDanmakuShowTime) {
                    iterator.remove();
                    mScrapDanmakus.add(danmaku);
                    track.haveCenterDanmaku = false;
                    mCurrentDanmakuCount--;
                }
            }
        }
    }

    /**
     * 根据手机的分辨率从 dip(像素) 的单位 转成为 px
     */
    public int dip2px(float dpValue) {
        return (int) (dpValue * mDensity + 0.5f);
    }

    private enum DanmakuState {
        IDLE,
        Prepared,
        Playing,
        Pause,
    }

    private class DanmakuTimerTask extends TimerTask {

        @Override
        public void run() {
            if (mDanmakuState == DanmakuState.Playing) {
                if (mStartTime == -1) {
                    mStartTime = System.currentTimeMillis();
                }
                final long currentTime = System.currentTimeMillis();
                final long deltaTime = currentTime - mStartTime;
                mStartTime = currentTime;
                mCurrentTime += deltaTime;
                for (int i = mLastAddDanmakuIndex + 1; i < mDanmakus.size(); i++) {
                    Danmaku danmaku = mDanmakus.get(i);
                    if (mCurrentTime - danmaku.getTime() >= 0) {
                        addDanamku(danmaku);
                        mLastAddDanmakuIndex = i;
                    } else {
                        break;
                    }
                }
            }
        }
    }

    // <d p="23.826000213623,1,25,16777215,1422201084,0,057075e9,757076900">我从未见过如此厚颜无耻之猴</d>
    // 0:时间(弹幕出现时间)
    // 1:类型(1从右至左滚动弹幕|6从左至右滚动弹幕|5顶端固定弹幕|4底端固定弹幕|7高级弹幕|8脚本弹幕)
    // 2:字号(弹幕大小 12非常小,16特小,18小,25中,36大,45很大,64特别大)
    // 3:颜色
    // 4:时间戳 ?
    // 5:弹幕池id
    // 6:用户hash
    // 7:弹幕id

    //弹幕轨道类
    private class DanmakuTrack {
        LinkedList<DanmakuWrapped> mDanmakus;
        DanmakuWrapped mWaitToAddDanamku; //等待加入轨道的弹幕
        float y;

        boolean haveCenterDanmaku;
        DanmakuWrapped lastScrollDanmaku;

        DanmakuTrack() {
            mDanmakus = new LinkedList<>();
            haveCenterDanmaku = false;
        }
    }

    private class DanmakuWrapped {

        private Danmaku danmaku;

        private float x;
        private long showTime;
        private float speed;

        private TextPaint paint; //文本画笔
        private TextPaint strokePaint; //描边画笔

        private float textHeight;
        private float textWidth;
        private float baseLineOffset;

        private DanmakuWrapped() {
            paint = new TextPaint();
            strokePaint = new TextPaint();
            strokePaint.setAntiAlias(true);
            strokePaint.setStrokeWidth(mStrokeWidth);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setFakeBoldText(true);

            paint.setStyle(Paint.Style.FILL);
            paint.setAntiAlias(true);
            paint.setFakeBoldText(true);
        }

        private void wrapDanmaku(Danmaku danmaku) {
            this.danmaku = danmaku;
            if (danmaku.getTextColor() == Color.WHITE) {
                strokePaint.setColor(Color.BLACK);
            } else {
                strokePaint.setColor(Color.WHITE);
            }
            paint.setColor(danmaku.getTextColor());
            float textSize = dip2px(danmaku.getTextSize()) * mScaleTextRatio;
            textSize = textSize > mPeerTrackHeight ? mPeerTrackHeight : textSize;
            paint.setTextSize(textSize);
            strokePaint.setTextSize(textSize);
            TextPaint.FontMetrics fontMetrics = paint.getFontMetrics();
            textHeight = fontMetrics.descent - fontMetrics.ascent;
            textWidth = paint.measureText(danmaku.getContent());
            baseLineOffset = -fontMetrics.ascent;
            speed = mSpeedRatio * (mScreenWidth + getWidth()) * 16.7f / mScrollDanmakuShowTime;
        }

        private void draw(Canvas canvas, float y) {
            canvas.drawText(danmaku.getContent(), x, y + baseLineOffset, paint);
            canvas.drawText(danmaku.getContent(), x, y + baseLineOffset, strokePaint);
        }

        private float getWidth() {
            return textWidth;
        }

        private float getHeight() {
            return textHeight;
        }

        private int getType() {
            return danmaku.getType();
        }
    }
}
