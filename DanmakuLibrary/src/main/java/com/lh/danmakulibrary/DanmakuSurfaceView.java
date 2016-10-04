package com.lh.danmakulibrary;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.ListIterator;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Created by liuhui on 2016/10/4.
 */

public class DanmakuSurfaceView extends SurfaceView implements SurfaceHolder.Callback {

    public static final int IDLE = 0;
    public static final int PREPARED = 1;
    public static final int PLAYING = 2;
    public static final int PAUSE = 3;
    public static final int SEEKING = 4;

    private static final long REFRESH_TIME = 100;
    private static final int MAX_TEXT_SIZE = 21;

    private int mScreenHeight;
    private int mScreenWidth;

    private float mDensity;
    private float mScaleTextRatio = 0.9f; //文本缩放倍率
    private float mSpeedRatio = 1.0f; //速度倍率
    private int mMaxDanmakuTrackCount = 20; //显示弹幕的轨道数(最大)
    private long mCenterDanmakuShowTime = 4000; //居中弹幕的显示时长
    private long mScrollDanmakuShowTime = 8000; //滚动弹幕显示时长

    private float mStrokeWidth = 0.8f; //文本描边宽度
    private float mPeerTrackHeight; //每条轨道的高度
    private float mTrackMargin; //每条轨道的间距
    private int mMaxDanmakuCount = 40; //最大同时显示的弹幕数量
    private int mCurrentDanmakuCount = 0; //当前同屏的弹幕数量

    private boolean mShowDebugInfo; //是否显示Debug信息
    private boolean mAvoidOverLapping; //是否允许弹幕重叠

    private boolean mShowDanmaku; //是否显示弹幕

    private TextPaint mDebugTextPaint;
    private int mDebugTextSize;
    private int mFps;
    private int mFrame;
    private long mDebugStartTime = 0;
    private int mNewCount = 0;

    private Timer mTimer;
    private DanmakuTimerTask mTask;
    private DrawThread mDrawThread;

    private ArrayList<Danmaku> mDanmakus; //弹幕数据源

    private LinkedList<DanmakuWrapped> mScrapDanmakus; //弹幕回收池
    private ArrayList<DanmakuTrack> mDanmakuTracks; //弹幕轨道数组

    private int mDanmakuState;

    private long mCurrentTime = -1;
    private long mStartTime = -1;

    private int mLastAddDanmakuIndex; //最后出现的弹幕的索引

    private SurfaceHolder mSurfaceHolder;

    public DanmakuSurfaceView(Context context) {
        super(context);
        init();
    }

    public DanmakuSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public DanmakuSurfaceView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        mSurfaceHolder = holder;
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        mScreenHeight = height;
        mScreenWidth = width;
        if (mDanmakuTracks.size() == 0) {
            prepareDanmakuTrack();
        } else {
            System.out.println("change");
            int preState = mDanmakuState;
            pause();
            clearAllDanamku();
            measureTrack();
            if (preState == PLAYING) {
                resume();
            }
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        System.out.println("surfaceDestroyed");
        mSurfaceHolder = null;
    }

    //初始化
    private void init() {
        mShowDebugInfo = false;
        mAvoidOverLapping = true;

        mShowDanmaku = true;
        mDanmakuState = IDLE;
        mDensity = getResources().getDisplayMetrics().density;

        mDebugTextPaint = new TextPaint();
        mDebugTextSize = dip2px(15);
        mDebugTextPaint.setTextSize(mDebugTextSize);
        mDebugTextPaint.setColor(Color.WHITE);

        mDanmakuTracks = new ArrayList<>();
        mScrapDanmakus = new LinkedList<>();
        getHolder().addCallback(this);
    }

    public void setShowDebugInfo(boolean showDebugInfo) {
        this.mShowDebugInfo = showDebugInfo;
    }

    public void setAvoidOverLapping(boolean avoidOverLapping) {
        this.mAvoidOverLapping = avoidOverLapping;
    }

    public void setDanmakuSource(ArrayList<Danmaku> danmakuSource) {
        this.mDanmakus = danmakuSource;
    }

    public void start() {
        if (mDanmakuState == PREPARED) {
            mDanmakuState = PLAYING;
            mDrawThread = new DrawThread();
            mTimer = new Timer();
            mTask = new DanmakuTimerTask();
            mDrawThread.start();
            mTimer.scheduleAtFixedRate(mTask, 0, REFRESH_TIME);
        }
    }

    public void pause() {
        if (mDanmakuState == PLAYING && mTimer != null && mTask != null) {
            mDanmakuState = PAUSE;
            mDrawThread = null;
            mTimer.cancel();
            mTask.cancel();
            mStartTime = -1;
            mTimer = null;
            mTask = null;
        }
    }

    public void resume() {
        if (mDanmakuState == PAUSE) {
            mDanmakuState = PLAYING;
            mDrawThread = new DrawThread();
            mTimer = new Timer();
            mTask = new DanmakuTimerTask();
            mDrawThread.start();
            mTimer.scheduleAtFixedRate(mTask, 0, REFRESH_TIME);

        }
    }

    public void seekTo(long time) {
        if (mDanmakuState != IDLE) {
            int preState = mDanmakuState;
            mDanmakuState = SEEKING;
            mCurrentTime = time;
            for (int i = 0; i < mDanmakus.size(); i++) {
                Danmaku danmaku = mDanmakus.get(i);
                if (danmaku.getTime() >= time) {
                    mLastAddDanmakuIndex = i;
                    break;
                }
            }
            clearAllDanamku();
            mDanmakuState = preState;
        }
    }

    public void stop() {
        mDanmakuState = PREPARED;
        mDrawThread = null;
        if (mTimer != null) {
            mTimer.cancel();
        }
        if (mTask != null) {
            mTask.cancel();
        }
        for (DanmakuTrack track : mDanmakuTracks) {
            track.clear();
        }
        mScrapDanmakus.clear();
        mTimer = null;
        mTask = null;
        mCurrentTime = -1;
        mStartTime = -1;
        mNewCount = 0;
        mCurrentDanmakuCount = 0;
        mLastAddDanmakuIndex = 0;
    }

    public void release() {
        stop();
        mDanmakuState = IDLE;
        mDanmakuTracks.clear();
        System.out.println("release_finish");
    }

    public void show() {
        if (!mShowDanmaku) {
            mShowDanmaku = true;
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
        return mDanmakuState != IDLE;
    }

    public boolean isPaused() {
        return mDanmakuState == PAUSE;
    }

    public long getCurrentTime() {
        return mCurrentTime;
    }

    private void measureTrack() {
        System.out.println("measure_start");
        mDanmakuTracks.clear();
        TextPaint measureTextPaint = new TextPaint();
        measureTextPaint.setAntiAlias(true);
        measureTextPaint.setFakeBoldText(true);
        measureTextPaint.setTextSize(dip2px(MAX_TEXT_SIZE) * mScaleTextRatio);
        TextPaint.FontMetrics fontMetrics = measureTextPaint.getFontMetrics();
        mPeerTrackHeight = fontMetrics.bottom - fontMetrics.top;
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
        System.out.println("measure_end");
    }

    //准备弹幕轨道
    private void prepareDanmakuTrack() {
        if (mDanmakuState == IDLE) {
            mDanmakuState = PREPARED;
            measureTrack();
        }
    }

    //添加一条弹幕数据
    public synchronized void addDanamku(Danmaku danmaku) {
        if (mCurrentDanmakuCount >= mMaxDanmakuCount || mDanmakuState == IDLE) {
            return;
        }
        DanmakuWrapped danmakuWrapped = wrapDanmaku(danmaku);
        if (danmaku.getType() == 1) { //滚动弹幕
            int trackIndex = mAvoidOverLapping ? findAvaliableScrollTrackAvoidOverLapping(danmakuWrapped) : findAvaliableScrollTrack();
            if (trackIndex >= 0 && trackIndex < mDanmakuTracks.size()) {
                danmakuWrapped.x = getWidth();
                addScrollDanmaku(danmakuWrapped, trackIndex);
            } else {
                mScrapDanmakus.add(danmakuWrapped); //不能添加就回收
            }
        } else if (danmaku.getType() == 5) { //顶部弹幕
            int trackIndex = findAvaliableTopCenterTrack();
            if (trackIndex >= 0 && trackIndex < mDanmakuTracks.size()) {
                danmakuWrapped.x = (getWidth() - danmakuWrapped.getWidth()) / 2;
                danmakuWrapped.showTime = System.currentTimeMillis();
                addCenterDanmaku(danmakuWrapped, trackIndex);
            } else {
                mScrapDanmakus.add(danmakuWrapped); //不能添加就回收
            }
        } else if (danmaku.getType() == 4) { //底部弹幕
            int trackIndex = findAvaliableBottomCenterTrack();
            if (trackIndex >= 0 && trackIndex < mDanmakuTracks.size()) {
                danmakuWrapped.x = (getWidth() - danmakuWrapped.getWidth()) / 2;
                danmakuWrapped.showTime = System.currentTimeMillis();
                addCenterDanmaku(danmakuWrapped, trackIndex);
            } else {
                mScrapDanmakus.add(danmakuWrapped); //不能添加就回收
            }
        }
    }

    private void addCenterDanmaku(DanmakuWrapped danmakuWrapped, int trackIndex) {
        mCurrentDanmakuCount++;
        mDanmakuTracks.get(trackIndex).addCenterDanmaku(danmakuWrapped);
    }

    private void addScrollDanmaku(DanmakuWrapped danmakuWrapped, int trackIndex) {
        mCurrentDanmakuCount++;
        mDanmakuTracks.get(trackIndex).addScrollDanmaku(danmakuWrapped);
    }

    private void clearAllDanamku() {
        for (int i = 0; i < mDanmakuTracks.size(); i++) {
            DanmakuTrack track = mDanmakuTracks.get(i);
            mScrapDanmakus.addAll(track.mDanmakus);
            track.haveCenterDanmaku = false;
            if (track.mWaitToAddCenterDanamku != null) {
                mScrapDanmakus.add(track.mWaitToAddCenterDanamku);
                track.mWaitToAddCenterDanamku = null;
            }
            if (track.mWaitToAddScrollDanamku != null) {
                mScrapDanmakus.add(track.mWaitToAddScrollDanamku);
                track.mWaitToAddScrollDanamku = null;
            }
            track.clear();
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
    private synchronized int findAvaliableScrollTrack() {
        for (int i = 0; i < mDanmakuTracks.size(); i++) {
            DanmakuTrack track = mDanmakuTracks.get(i);
            if (track.canAddScrollDanmaku()) {
                return i;
            }
        }
        return -1;
    }

    //寻找一条可用的滚动弹幕轨道(避免重叠)
    private synchronized int findAvaliableScrollTrackAvoidOverLapping(DanmakuWrapped danmakuWrapped) {
        for (int i = 0; i < mDanmakuTracks.size(); i++) {
            DanmakuTrack track = mDanmakuTracks.get(i);
            if (track.canAddScrollDanmakuAvoidOverLapping(danmakuWrapped)) {
                return i;
            }
        }
        return -1;
    }

    //寻找一条可用的顶部居中弹幕轨道
    private synchronized int findAvaliableTopCenterTrack() {
        for (int i = 0; i < mDanmakuTracks.size(); i++) {
            DanmakuTrack track = mDanmakuTracks.get(i);
            if (track.canAddcenterDanmaku()) {
                return i;
            }
        }
        return -1;
    }

    //寻找一条可用的底部居中弹幕轨道
    private synchronized int findAvaliableBottomCenterTrack() {
        for (int i = mDanmakuTracks.size() - 1; i >= 0; i--) {
            DanmakuTrack track = mDanmakuTracks.get(i);
            if (track.canAddcenterDanmaku()) {
                return i;
            }
        }
        return -1;
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

    /**
     * 根据手机的分辨率从 dip(像素) 的单位 转成为 px
     */
    public int dip2px(float dpValue) {
        return (int) (dpValue * mDensity + 0.5f);
    }

    private void doDraw() {
        if (mSurfaceHolder == null || mDanmakuState == PAUSE || mDanmakuState == SEEKING) {
            return;
        }
        Canvas canvas = mSurfaceHolder.lockCanvas();
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        for (int i = 0; i < mDanmakuTracks.size(); i++) {
            DanmakuTrack track = mDanmakuTracks.get(i);
            if (mShowDanmaku) {
                track.draw(canvas);
            }
        }
        if (mShowDebugInfo) {
            drawDebugInfo(canvas);
        }
        if (mSurfaceHolder != null) {
            mSurfaceHolder.unlockCanvasAndPost(canvas);
        }
    }

    private class DrawThread extends Thread {
        @Override
        public void run() {
            while (mDanmakuState == PLAYING || mDanmakuState == SEEKING) {
                long preTime = System.currentTimeMillis();
                doDraw();
                try {
                    Thread.sleep(Math.max(16 + preTime - System.currentTimeMillis(), 0));
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private class DanmakuTimerTask extends TimerTask {

        private long currentTime;
        private long deltaTime;
        private int index;

        @Override
        public void run() {
            if (mDanmakuState == PLAYING) {
                if (mStartTime == -1) {
                    mStartTime = System.currentTimeMillis();
                }
                currentTime = System.currentTimeMillis();
                deltaTime = currentTime - mStartTime;
                mStartTime = currentTime;
                mCurrentTime += deltaTime;
                for (index = mLastAddDanmakuIndex + 1; index < mDanmakus.size(); index++) {
                    Danmaku danmaku = mDanmakus.get(index);
                    if (mCurrentTime - danmaku.getTime() >= 0) {
                        addDanamku(danmaku);
                        mLastAddDanmakuIndex = index;
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
        private LinkedList<DanmakuWrapped> mDanmakus;
        private DanmakuWrapped mWaitToAddCenterDanamku; //等待加入轨道的弹幕
        private DanmakuWrapped mWaitToAddScrollDanamku; //等待加入轨道的弹幕
        private ListIterator<DanmakuWrapped> mDanmakusIterator;
        private float y;

        private boolean haveCenterDanmaku;

        private DanmakuTrack() {
            mDanmakus = new LinkedList<>();
            mDanmakusIterator = mDanmakus.listIterator();
            haveCenterDanmaku = false;
        }

        private boolean canAddcenterDanmaku() {
            return !haveCenterDanmaku && mWaitToAddCenterDanamku == null;
        }

        private DanmakuWrapped findLastScrollDanmaku() {
            DanmakuWrapped lastDanmaku = null;
            if (mWaitToAddScrollDanamku != null) {
                lastDanmaku = mWaitToAddScrollDanamku;
            } else {
                ListIterator<DanmakuWrapped> listIterator = getLastIterator();
                while (listIterator.hasPrevious()) {
                    DanmakuWrapped pre = listIterator.previous();
                    if (pre.getType() == 1) {
                        lastDanmaku = pre;
                        break;
                    }
                }
            }
            return lastDanmaku;
        }

        @SuppressWarnings("RedundantIfStatement")
        private boolean canAddScrollDanmaku() {
            DanmakuWrapped lastScrollDanmaku = findLastScrollDanmaku();
            if (lastScrollDanmaku == null || lastScrollDanmaku.isDisAppear()) {
                return true;
            } else {
                if (lastScrollDanmaku.isTotallyAppearFromLeft()) {
                    return true;
                } else {
                    return false;
                }
            }
        }


        @SuppressWarnings("RedundantIfStatement")
        private boolean canAddScrollDanmakuAvoidOverLapping(DanmakuWrapped addDanmaku) {
            DanmakuWrapped lastScrollDanmaku = findLastScrollDanmaku();
            if (lastScrollDanmaku == null || lastScrollDanmaku.isDisAppear()) {
                return true;
            } else {
                if (lastScrollDanmaku.isTotallyAppearFromLeft()) {
                    if (lastScrollDanmaku.checkWillOverLapping(addDanmaku)) {
                        return false;
                    } else {
                        return true;
                    }
                } else {
                    return false;
                }
            }
        }

        private void addScrollDanmaku(DanmakuWrapped danmakuWrapped) {
            mWaitToAddScrollDanamku = danmakuWrapped;
        }

        private void addCenterDanmaku(DanmakuWrapped danmakuWrapped) {
            mWaitToAddCenterDanamku = danmakuWrapped;
            haveCenterDanmaku = true;
        }


        private void draw(Canvas canvas) {
            if (mWaitToAddCenterDanamku != null) {
                getLastIterator().add(mWaitToAddCenterDanamku);
                mWaitToAddCenterDanamku = null;
            }
            if (mWaitToAddScrollDanamku != null) {
                getLastIterator().add(mWaitToAddScrollDanamku);
                mWaitToAddScrollDanamku = null;
            }
            ListIterator<DanmakuWrapped> listIterator = getFirstIterator();
            while (listIterator.hasNext()) {
                DanmakuWrapped danmaku = listIterator.next();
                danmaku.draw(canvas, y);
                if (danmaku.danmaku.getType() == 1) {
                    if (danmaku.isDisAppear()) {
                        mScrapDanmakus.add(danmaku);
                        listIterator.remove();
                        mCurrentDanmakuCount--;
                    } else {
                        danmaku.x -= danmaku.speed;
                    }
                } else if (danmaku.getType() == 4 || danmaku.getType() == 5) {
                    if (System.currentTimeMillis() - danmaku.showTime > mCenterDanmakuShowTime) {
                        mScrapDanmakus.add(danmaku);
                        listIterator.remove();
                        haveCenterDanmaku = false;
                        mCurrentDanmakuCount--;
                    }
                }
            }
        }

        private ListIterator<DanmakuWrapped> getFirstIterator() {
            if (mDanmakusIterator != null) {
                while (mDanmakusIterator.hasPrevious()) {
                    mDanmakusIterator.previous();
                }
            } else {
                mDanmakusIterator = mDanmakus.listIterator();
            }
            return mDanmakusIterator;
        }

        private ListIterator<DanmakuWrapped> getLastIterator() {
            if (mDanmakusIterator != null) {
                while (mDanmakusIterator.hasNext()) {
                    mDanmakusIterator.next();
                }
            } else {
                int size = mDanmakus.size();
                mDanmakusIterator = mDanmakus.listIterator(size > 0 ? size - 1 : 0);
            }
            return mDanmakusIterator;
        }

        private void clear() {
            ListIterator<DanmakuWrapped> iterator = getFirstIterator();
            while (iterator.hasNext()) {
                iterator.next();
                iterator.remove();
            }
            mWaitToAddCenterDanamku = null;
            mWaitToAddScrollDanamku = null;
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
        private TextPaint.FontMetrics fontMetrics;

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

            fontMetrics = new Paint.FontMetrics();
        }

        private void wrapDanmaku(Danmaku danmaku) {
            this.danmaku = danmaku;
            if (danmaku.getTextColor() == Color.WHITE) {
                strokePaint.setColor(Color.BLACK);
            } else {
                strokePaint.setColor(Color.WHITE);
            }
            paint.setColor(danmaku.getTextColor());
            float textSize = dip2px(danmaku.getTextSize() > MAX_TEXT_SIZE ? MAX_TEXT_SIZE : danmaku.getTextSize()) * mScaleTextRatio;
            paint.setTextSize(textSize);
            strokePaint.setTextSize(textSize);
            paint.getFontMetrics(fontMetrics);
            textHeight = fontMetrics.descent - fontMetrics.top;
            textWidth = paint.measureText(danmaku.getContent());
            baseLineOffset = (mPeerTrackHeight + textHeight) / 2 - fontMetrics.descent;
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

        private boolean isDisAppear() {
            return x + textWidth < 0;
        }

        private boolean isTotallyAppearFromLeft() {
            return x + textWidth < mScreenWidth;
        }

        private boolean checkWillOverLapping(DanmakuWrapped nextDanmaku) {
            if (nextDanmaku.speed > speed && !isDisAppear()) {
                int lastDanmakuDisappearTime = (int) ((textWidth + x) / speed);
                int catchTime = (int) ((mScreenWidth - textWidth - x) / (nextDanmaku.speed - speed));
                return catchTime < lastDanmakuDisappearTime;
            } else {
                return false;
            }
        }
    }

}
