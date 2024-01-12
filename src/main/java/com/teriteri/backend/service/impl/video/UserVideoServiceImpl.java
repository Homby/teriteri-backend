package com.teriteri.backend.service.impl.video;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.teriteri.backend.mapper.UserVideoMapper;
import com.teriteri.backend.mapper.VideoMapper;
import com.teriteri.backend.pojo.UserVideo;
import com.teriteri.backend.pojo.Video;
import com.teriteri.backend.pojo.VideoStats;
import com.teriteri.backend.service.video.UserVideoService;
import com.teriteri.backend.service.video.VideoStatsService;
import com.teriteri.backend.utils.RedisUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Service
public class UserVideoServiceImpl implements UserVideoService {

    @Autowired
    private UserVideoMapper userVideoMapper;

    @Autowired
    private VideoStatsService videoStatsService;

    @Autowired
    private RedisUtil redisUtil;

    @Autowired
    @Qualifier("taskExecutor")
    private Executor taskExecutor;

    /**
     * 更新播放次数以及最近播放时间，顺便返回记录信息，没有记录则创建新记录
     * @param uid   用户ID
     * @param vid   视频ID
     * @return 更新后的数据信息
     */
    @Override
    public UserVideo updatePlay(Integer uid, Integer vid) {
        QueryWrapper<UserVideo> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("uid", uid).eq("vid", vid);
        UserVideo userVideo = userVideoMapper.selectOne(queryWrapper);
        if (userVideo == null) {
            // 记录不存在，创建新记录
            userVideo = new UserVideo(null, uid, vid, 1, 0, 0, 0, 0, new Date());
            userVideoMapper.insert(userVideo);
        } else if (System.currentTimeMillis() - userVideo.getRecentTime().getTime() <= 30000) {
            // 如果最近30秒内播放过则不更新记录，直接返回
            return userVideo;
        } else {
            userVideo.setPlay(userVideo.getPlay() + 1);
            userVideo.setRecentTime(new Date());
            userVideoMapper.updateById(userVideo);
        }
        // 异步线程更新video表和redis
        CompletableFuture.runAsync(() -> {
            redisUtil.zset("user_video_history:" + uid, vid);   // 添加到/更新观看历史记录
            videoStatsService.updateStats(vid, "play", true, 1);
        }, taskExecutor);
        return userVideo;
    }

    /**
     * 点赞或点踩，返回更新后的信息
     * @param uid   用户ID
     * @param vid   视频ID
     * @param isLove    赞还是踩 true赞 false踩
     * @param isSet     设置还是取消  true设置 false取消
     * @return  更新后的信息
     */
    @Override
    public UserVideo setLoveOrUnlove(Integer uid, Integer vid, boolean isLove, boolean isSet) {
        QueryWrapper<UserVideo> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("uid", uid).eq("vid", vid);
        UserVideo userVideo = userVideoMapper.selectOne(queryWrapper);
        if (isLove && isSet) {
            // 点赞
            if (userVideo.getLove() == 1) {
                // 原本就点了赞就直接返回
                return userVideo;
            }
            userVideo.setLove(1);
            UpdateWrapper<UserVideo> updateWrapper = new UpdateWrapper<>();
            updateWrapper.eq("uid", uid).eq("vid", vid);
            updateWrapper.setSql("love = 1");
            if (userVideo.getUnlove() == 1) {
                // 原本点了踩，要取消踩
                userVideo.setUnlove(0);
                updateWrapper.setSql("unlove = 0");
                CompletableFuture.runAsync(() -> {
                    videoStatsService.updateGoodAndBad(vid, true);
                }, taskExecutor);
            } else {
                // 原本没点踩，只需要点赞就行
                CompletableFuture.runAsync(() -> {
                    videoStatsService.updateStats(vid, "good", true, 1);
                }, taskExecutor);
            }
            userVideoMapper.update(null, updateWrapper);
        } else if (isLove) {
            // 取消点赞
            if (userVideo.getLove() == 0) {
                // 原本就没有点赞就直接返回
                return userVideo;
            }
            userVideo.setLove(0);
            UpdateWrapper<UserVideo> updateWrapper = new UpdateWrapper<>();
            updateWrapper.eq("uid", uid).eq("vid", vid);
            updateWrapper.setSql("love = 0");
            userVideoMapper.update(null, updateWrapper);
            CompletableFuture.runAsync(() -> {
                videoStatsService.updateStats(vid, "good", false, 1);
            }, taskExecutor);
        } else if (isSet) {
            // 点踩
            if (userVideo.getUnlove() == 1) {
                // 原本就点了踩就直接返回
                return userVideo;
            }
            userVideo.setUnlove(1);
            UpdateWrapper<UserVideo> updateWrapper = new UpdateWrapper<>();
            updateWrapper.eq("uid", uid).eq("vid", vid);
            updateWrapper.setSql("unlove = 1");
            if (userVideo.getLove() == 1) {
                // 原本点了赞，要取消赞
                userVideo.setLove(0);
                updateWrapper.setSql("love = 0");
                CompletableFuture.runAsync(() -> {
                    videoStatsService.updateGoodAndBad(vid, false);
                }, taskExecutor);
            } else {
                // 原本没点赞，只需要点踩就行
                CompletableFuture.runAsync(() -> {
                    videoStatsService.updateStats(vid, "bad", true, 1);
                }, taskExecutor);
            }
            userVideoMapper.update(null, updateWrapper);
        } else {
            // 取消点踩
            if (userVideo.getUnlove() == 0) {
                // 原本就没有点踩就直接返回
                return userVideo;
            }
            userVideo.setUnlove(0);
            UpdateWrapper<UserVideo> updateWrapper = new UpdateWrapper<>();
            updateWrapper.eq("uid", uid).eq("vid", vid);
            updateWrapper.setSql("unlove = 0");
            userVideoMapper.update(null, updateWrapper);
            CompletableFuture.runAsync(() -> {
                videoStatsService.updateStats(vid, "bad", false, 1);
            }, taskExecutor);
        }
        return userVideo;
    }
}
