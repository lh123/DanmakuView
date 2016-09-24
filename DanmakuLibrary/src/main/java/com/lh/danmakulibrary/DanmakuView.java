package com.lh.danmakulibrary;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Created by liuhui on 2016/9/8.
 * 弹幕控件
 */
@SuppressWarnings("FieldCanBeLocal")
public class DanmakuView extends View {

    private static final long REFRESH_TIME = 100;

    private int mScreenHeight;
    private int mScreenWidth;

    private float mDensity;
    private float mScaleTextRatio = 1.2f; //文本缩放倍率
    private float mSpeedRatio = 1.2f; //速度倍率
    private int mDanmakuTrackCount = 14; //显示弹幕的轨道数
    private long mCenterDanmakuShowTime = 4000; //居中弹幕的显示时长
    private float mStrokeWidth = 0.8f; //文本描边宽度

    private boolean mShowDebugInfo; //是否显示Debug信息

    private TextPaint mDebugTextPaint;
    private int mFps;
    private int mFrame;
    private long mDebugStartTime = 0;

    private Timer mTimer;
    private DanmakuTimerTask mTask;

    private ArrayList<Danmaku> mDanmakus;

    private LinkedList<DanmakuWrapped> mScrapDanmakus = new LinkedList<>(); //弹幕回收池
    private ArrayList<DanmakuTrack> mDanmakuTracks; //弹幕轨道数组

    private DanmakuState mDanmakuState;

    private long mCurrentTime = -1;
    private long mStartTime = -1;

    private int mLastAddDanmakuIndex;

    private enum DanmakuState {
        IDLE,
        Prepared,
        Playing,
        Pause,
    }


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
        mDanmakuState = DanmakuState.IDLE;
        mDensity = getResources().getDisplayMetrics().density;

        mDebugTextPaint = new TextPaint();
        mDebugTextPaint.setTextSize(px2dip(20));
        mDebugTextPaint.setColor(Color.WHITE);

        mDanmakuTracks = new ArrayList<>();
        for (int i = 0; i < mDanmakuTrackCount; i++) {
            mDanmakuTracks.add(new DanmakuTrack(new LinkedList<DanmakuWrapped>()));
        }
    }

    public void setShowDebugInfo(boolean showDebugInfo) {
        this.mShowDebugInfo = showDebugInfo;
    }

    public void setDanmakuSource(ArrayList<Danmaku> danmakuSource) {
        this.mDanmakus = danmakuSource;
        mDanmakuState = DanmakuState.Prepared;
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
        } else if (mDanmakuState == DanmakuState.Prepared) {
            start();
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
        }
        mScrapDanmakus.clear();
        mTimer = null;
        mTask = null;
        mCurrentTime = -1;
        mStartTime = -1;
        mLastAddDanmakuIndex = 0;
        postInvalidate();
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
        int diffY = mScreenHeight / mDanmakuTrackCount;
        int currentY = 0;
        for (int i = 0; i < mDanmakuTracks.size(); i++) {
            DanmakuTrack track = mDanmakuTracks.get(i);
            track.y = currentY;
            currentY += diffY;
        }
    }

    //添加一条弹幕数据
    public synchronized void addDanamku(Danmaku danmaku) {
        DanmakuWrapped danmakuWrapped = wrapDanmaku(danmaku);
        if (danmaku.getType() == 1) {
            danmakuWrapped.x = getWidth();
            int trackIndex = findAvaliableTrack();
            if (trackIndex >= 0 && trackIndex < mDanmakuTracks.size()) {
                mDanmakuTracks.get(trackIndex).mDanmakus.add(danmakuWrapped);
            }
        } else {
            danmakuWrapped.x = (getWidth() - danmakuWrapped.getWidth()) / 2;
            danmakuWrapped.showTime = System.currentTimeMillis();
            int trackIndex = findAvaliableCenterTrack();
            if (trackIndex >= 0 && trackIndex < mDanmakuTracks.size()) {
                mDanmakuTracks.get(trackIndex).mDanmakus.add(danmakuWrapped);
                mDanmakuTracks.get(trackIndex).haveCenterDanmaku = true;
            }
        }
    }

    private void clearAllDanamku() {
        for (int i = 0; i < mDanmakuTracks.size(); i++) {
            DanmakuTrack track = mDanmakuTracks.get(i);
            mScrapDanmakus.addAll(track.mDanmakus);
            track.haveCenterDanmaku = false;
            track.mDanmakus.clear();
        }
    }

    //将添加的弹幕进行处理
    private DanmakuWrapped wrapDanmaku(Danmaku danmaku) {
        DanmakuWrapped danmakuWrapped;
        if (mScrapDanmakus.size() != 0) {
            danmakuWrapped = mScrapDanmakus.getFirst();
            mScrapDanmakus.removeFirst();
        } else {
            System.out.println("new");
            danmakuWrapped = new DanmakuWrapped();
        }
        danmakuWrapped.wrapDanmaku(danmaku);
        return danmakuWrapped;
    }

    //寻找一条可用的弹幕轨道
    private int findAvaliableTrack() {
        for (int i = 0; i < mDanmakuTracks.size(); i++) {
            DanmakuTrack track = mDanmakuTracks.get(i);
            if ((track.mDanmakus.size() == 0 && !track.haveCenterDanmaku) || (track.mDanmakus.size() == 1 && track.haveCenterDanmaku)) {
                return i;
            } else {
                LinkedList<DanmakuWrapped> danmakuWrappeds = mDanmakuTracks.get(i).mDanmakus;
                DanmakuWrapped lastScrollDanmaku = null;
                for (int j = danmakuWrappeds.size() - 1; j >= 0; j--) {
                    DanmakuWrapped danmakuWrapped = danmakuWrappeds.get(j);
                    if (danmakuWrapped.getType() == 1) {
                        lastScrollDanmaku = danmakuWrapped;
                        break;
                    }
                }
                if (lastScrollDanmaku != null && lastScrollDanmaku.x + lastScrollDanmaku.getWidth() < getWidth()) {
                    return i;
                }
            }
        }
        return -1;
    }

    //寻找一条可用的居中弹幕轨道
    private int findAvaliableCenterTrack() {
        for (int i = 0; i < mDanmakuTracks.size(); i++) {
            DanmakuTrack track = mDanmakuTracks.get(i);
            if (!track.haveCenterDanmaku) {
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
            for (int j = 0; j < track.mDanmakus.size(); j++) {
                DanmakuWrapped danmakuWrapped = track.mDanmakus.get(j);
                danmakuWrapped.draw(canvas, track.y);
            }
        }
        drawDebugInfo(canvas);
        refreshDanmakuView();
    }

    private void drawDebugInfo(Canvas canvas) {
        if (mShowDebugInfo) {
            mFrame++;
            final long nowTime = System.currentTimeMillis();
            final long deltaTime = nowTime - mDebugStartTime;
            if (deltaTime > 1000) {
                mFps = (int) (mFrame * 1000 / deltaTime);
                mFrame = 0;
                mDebugStartTime = nowTime;
            }
            int count = 0;
            for (int i = 0; i < mDanmakuTracks.size(); i++) {
                DanmakuTrack track = mDanmakuTracks.get(i);
                count += track.mDanmakus.size();
            }
            canvas.drawText(String.format(Locale.getDefault(), "fps:%d count:%d scrapCount:%d time:%.1fs", mFps, count, mScrapDanmakus.size(), mCurrentTime / 1000f), 10, mScreenHeight - mDebugTextPaint.getFontMetrics().bottom, mDebugTextPaint);
        }
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
        for (int i = 0; i < track.mDanmakus.size(); i++) {
            DanmakuWrapped danmaku = track.mDanmakus.get(i);
            if (danmaku.danmaku.getType() == 1) {
                if (danmaku.x + danmaku.getWidth() < 0) {
                    track.mDanmakus.remove(danmaku);
                    mScrapDanmakus.add(danmaku);
                    i--;
//                    System.out.println("remove");
                } else {
                    danmaku.x -= danmaku.speed;
                }
            } else {
                if (System.currentTimeMillis() - danmaku.showTime > mCenterDanmakuShowTime) {
                    track.mDanmakus.remove(danmaku);
                    mScrapDanmakus.add(danmaku);
                    track.haveCenterDanmaku = false;
                    i--;
//                    System.out.println("remove_center");
                }
            }
        }
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

    private class DanmakuTrack {
        LinkedList<DanmakuWrapped> mDanmakus;
        int y;
        boolean haveCenterDanmaku;

        DanmakuTrack(LinkedList<DanmakuWrapped> mDanmakus) {
            this.mDanmakus = mDanmakus;
            haveCenterDanmaku = false;
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

    private class DanmakuWrapped {

        private Danmaku danmaku;

        private float x;
        private long showTime;
        private float speed;

        private TextPaint paint;
        private TextPaint strokePaint;

        private DanmakuWrapped() {
            paint = new TextPaint(); //文本画笔
            strokePaint = new TextPaint(); //描边画笔
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
            strokePaint.setTextSize(px2dip(danmaku.getTextSize()) * mScaleTextRatio);
            paint.setColor(danmaku.getTextColor());
            paint.setTextSize(px2dip(danmaku.getTextSize()) * mScaleTextRatio);
            speed = (((float) getWidth() / mScreenWidth) + mDensity) * mSpeedRatio;
        }

        private void draw(Canvas canvas, int y) {
            canvas.drawText(danmaku.getContent(), x, y + getHeight(), paint);
            canvas.drawText(danmaku.getContent(), x, y + getHeight(), strokePaint);
        }

        private int getWidth() {
            return (int) paint.measureText(danmaku.getContent());
        }

        private int getHeight() {
            return px2dip(danmaku.getTextSize());
        }

        private int getType() {
            return danmaku.getType();
        }
    }

    /**
     * 根据手机的分辨率从 px(像素) 的单位 转成为 dp
     */
    public int px2dip(float pxValue) {
        return (int) (pxValue * mDensity + 0.5f);
    }
}
