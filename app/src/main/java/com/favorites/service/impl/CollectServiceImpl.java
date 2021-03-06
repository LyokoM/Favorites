package com.favorites.service.impl;

import java.io.IOException;
import java.net.URL;
import java.util.*;


import com.favorites.cache.CacheService;
import com.favorites.comm.Const;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Whitelist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import com.favorites.domain.Collect;
import com.favorites.domain.Favorites;
import com.favorites.domain.Praise;
import com.favorites.domain.User;
import com.favorites.domain.enums.CollectType;
import com.favorites.domain.enums.IsDelete;
import com.favorites.domain.view.CollectSummary;
import com.favorites.domain.view.CollectView;
import com.favorites.repository.CollectRepository;
import com.favorites.repository.CommentRepository;
import com.favorites.repository.FavoritesRepository;
import com.favorites.repository.FollowRepository;
import com.favorites.repository.PraiseRepository;
import com.favorites.repository.UserRepository;
import com.favorites.service.CollectService;
import com.favorites.service.FavoritesService;
import com.favorites.service.NoticeService;
import com.favorites.utils.DateUtils;
import com.favorites.utils.StringUtil;
import org.springframework.transaction.annotation.Transactional;

@Service("collectService")
public class CollectServiceImpl extends CacheService implements CollectService {
    protected Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private CollectRepository collectRepository;
    @Autowired
    private FavoritesRepository favoritesRepository;
    @Autowired
    private FavoritesService favoritesService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private NoticeService noticeService;
    @Autowired
    private PraiseRepository praiseRepository;
    @Autowired
    private CommentRepository commentRepository;
    @Autowired
    private FollowRepository followRepository;

    /**
     * 展示收藏列表
     *
     * @param type
     * @param userId
     * @param pageable
     * @return
     * @author Lyoko
     * @date 2019年11月24日
     */
    @Override
    public List<CollectSummary> getCollects(String type, Long userId, Pageable pageable, Long favoritesId, Long specUserId) {
        // TODO Auto-generated method stub
        Page<CollectView> views = null;
        if ("my".equals(type)) {
            List<Long> userIds = followRepository.findMyFollowIdByUserId(userId);
            if (userIds == null || userIds.size() == 0) {
                views = collectRepository.findViewByUserId(userId, pageable);
            } else {
                views = collectRepository.findViewByUserIdAndFollows(userId, userIds, pageable);
            }
        } else if ("myself".equals(type)) {
            views = collectRepository.findViewByUserId(userId, pageable);
        } else if ("explore".equals(type)) { //发现功能

            views = collectRepository.findDiscoverView(userId, pageable);
        } else if ("others".equals(type)) {
            views = collectRepository.findViewByUserIdAndType(userId, pageable, CollectType.PUBLIC);
            if (null != specUserId) {
                userId = specUserId;
            }
        } else if ("otherpublic".equals(type)) {
            views = collectRepository.findViewByUserIdAndTypeAndFavoritesId(userId, pageable, CollectType.PUBLIC, favoritesId);
            if (null != specUserId) {
                userId = specUserId;
            }
        } else if ("garbage".equals(type)) {
            views = collectRepository.findViewByUserIdAndIsDelete(userId, pageable);
        } else {
            views = collectRepository.findViewByFavoritesId(Long.parseLong(type), pageable);
        }
        return convertCollect(views, userId);
    }


    /**
     * @param key
     * @param userId
     * @return
     * @author Lyoko
     * @date 2019年11月25日
     */
    @Override
    public List<CollectSummary> searchMy(Long userId, String key, Pageable pageable) {
        // TODO Auto-generated method stub
        Page<CollectView> views = collectRepository.searchMyByKey(userId, "%" + key + "%", pageable);
        return convertCollect(views, userId);
    }


    /**
     * @param userId
     * @param key
     * @param pageable
     * @return
     * @author Lyoko
     * @date 2019年11月26日
     */
    @Override
    public List<CollectSummary> searchOther(Long userId, String key, Pageable pageable) {
        // TODO Auto-generated method stub
        Page<CollectView> views = collectRepository.searchOtherByKey(userId, "%" + key + "%", pageable);
        return convertCollect(views, userId);
    }

    /**
     * @return
     * @author Lyoko
     * @date 2019年11月11日
     */
    private List<CollectSummary> convertCollect(Page<CollectView> views, Long userId) {
        List<CollectSummary> summarys = new ArrayList<CollectSummary>();
        for (CollectView view : views) {
            CollectSummary summary = new CollectSummary(view);
            summary.setPraiseCount(praiseRepository.countByCollectId(view.getId()));
            summary.setCommentCount(commentRepository.countByCollectId(view.getId()));
            Praise praise = praiseRepository.findByUserIdAndCollectId(userId, view.getId());
            if (praise != null) {
                summary.setPraise(true);
            } else {
                summary.setPraise(false);
            }
            summary.setCollectTime(DateUtils.getTimeFormatText(view.getCreateTime()));
            summarys.add(summary);
        }
        return summarys;
    }

    /**
     * 收藏文章
     *
     * @param collect
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveCollect(Collect collect) {
        if (collect.getType() == null) {
            collect.setType(CollectType.PUBLIC);
        } else {
            collect.setType(CollectType.PRIVATE);
        }
        if (StringUtils.isNotBlank(collect.getNewFavorites())) {
            collect.setFavoritesId(createfavorites(collect));
        }
        if (StringUtils.isBlank(collect.getDescription())) {
            collect.setDescription(collect.getTitle());
        }
//		if(collect.getUrl().contains("?")){
//			collect.setUrl(collect.getUrl().substring(0,collect.getUrl().indexOf("?")));
//		}
        collect.setIsDelete(IsDelete.NO);
        collect.setCreateTime(DateUtils.getCurrentTime());
        collect.setLastModifyTime(DateUtils.getCurrentTime());
        collectRepository.save(collect);
        favoritesService.countFavorites(collect.getFavoritesId());
        noticeFriends(collect);
    }

    /**
     * 修改文章
     *
     * @param newCollect
     * @author Lyoko
     * @date 2019年11月24日
     */
    @Override
    @Transactional
    public void updateCollect(Collect newCollect) {
        Collect collect = collectRepository.findById(newCollect.getId().longValue());
        if (StringUtils.isNotBlank(newCollect.getNewFavorites())) {
            collect.setFavoritesId(createfavorites(collect));
        } else if (!collect.getFavoritesId().equals(newCollect.getFavoritesId()) && !IsDelete.YES.equals(collect.getIsDelete())) {
            favoritesService.countFavorites(collect.getFavoritesId());
            favoritesService.countFavorites(newCollect.getFavoritesId());
            favoritesRepository.reduceCountById(collect.getFavoritesId(), DateUtils.getCurrentTime());
            collect.setFavoritesId(newCollect.getFavoritesId());
        }
        if (IsDelete.YES.equals(collect.getIsDelete())) {
            collect.setIsDelete(IsDelete.NO);
            if (StringUtils.isBlank(newCollect.getNewFavorites())) {
                favoritesService.countFavorites(newCollect.getFavoritesId());
                collect.setFavoritesId(newCollect.getFavoritesId());
            }
        }
        if (newCollect.getType() == null) {
            collect.setType(CollectType.PUBLIC);
        } else {
            collect.setType(CollectType.PRIVATE);
        }
        collect.setTitle(newCollect.getTitle());
        collect.setDescription(newCollect.getDescription());
        collect.setLogoUrl(newCollect.getLogoUrl());
        collect.setRemark(newCollect.getRemark());
        collect.setLastModifyTime(DateUtils.getCurrentTime());
        collectRepository.save(collect);
        noticeFriends(collect);
    }


    /**
     * 收藏别人的文章
     *
     * @param collect
     * @author Lyoko
     * @date 2019年11月31日
     */
    @Override
    @Transactional
    public void otherCollect(Collect collect) {
        Collect other = collectRepository.findById(collect.getId().longValue());
        //收藏别人文章默认给点赞
        collectLike(collect.getUserId(), other.getId());
        if (StringUtils.isNotBlank(collect.getNewFavorites())) {
            collect.setFavoritesId(createfavorites(collect));
        }
        collect.setId(null);
        collect.setIsDelete(IsDelete.NO);
        if (collect.getType() == null) {
            collect.setType(CollectType.PUBLIC);
        } else {
            collect.setType(CollectType.PRIVATE);
        }
        if (StringUtils.isBlank(collect.getDescription())) {
            collect.setDescription(collect.getTitle());
        }
        collect.setUrl(other.getUrl());
        collect.setLastModifyTime(DateUtils.getCurrentTime());
        collect.setCreateTime(DateUtils.getCurrentTime());
        collectRepository.save(collect);
        noticeFriends(collect);
        favoritesService.countFavorites(collect.getFavoritesId());
    }

    /**
     * 验证是否重复收藏
     *
     * @param collect
     * @return
     */
    @Override
    public boolean checkCollect(Collect collect) {
        if (StringUtils.isNotBlank(collect.getNewFavorites())) {
            // url+favoritesId+userId
            Favorites favorites = favoritesRepository.findByUserIdAndName(collect.getUserId(), collect.getNewFavorites());
            if (null == favorites) {
                return true;
            } else {
                List<Collect> list = collectRepository.findByFavoritesIdAndUrlAndUserIdAndIsDelete(favorites.getId(), collect.getUrl(), collect.getUserId(), IsDelete.NO);
                if (null != list && list.size() > 0) {
                    return false;
                } else {
                    return true;
                }
            }
        } else {
            if (collect.getId() != null) {
                Collect c = collectRepository.findById(collect.getId().longValue());
                if (c.getFavoritesId().equals(collect.getFavoritesId())) {
                    return true;
                } else {
                    List<Collect> list = collectRepository.findByFavoritesIdAndUrlAndUserIdAndIsDelete(collect.getFavoritesId(), collect.getUrl(), collect.getUserId(), IsDelete.NO);
                    if (null != list && list.size() > 0) {
                        return false;
                    } else {
                        return true;
                    }
                }
            } else {
                List<Collect> list = collectRepository.findByFavoritesIdAndUrlAndUserIdAndIsDelete(collect.getFavoritesId(), collect.getUrl(), collect.getUserId(), IsDelete.NO);
                if (null != list && list.size() > 0) {
                    return false;
                } else {
                    return true;
                }
            }
        }
    }

//    /**
//     * 导入收藏文章
//     */
//    @Override
//    public void importHtml(Map<String, String> map, Long favoritesId, Long userId, String type) {
//        List<Collect> collects = new ArrayList<>();
//        try {
//            for (Map.Entry<String, String> entry : map.entrySet()) {
//                Map<String, String> result = HtmlUtil.getCollectFromUrl(entry.getKey());
//                Collect collect = new Collect();
//                collect.setCharset(result.get("charset"));
//                if (StringUtils.isBlank(result.get("title"))) {
//                    collect.setTitle(entry.getValue());
//                } else {
//                    collect.setTitle(result.get("title"));
//                }
//                if (StringUtils.isBlank(result.get("description"))) {
//                    collect.setDescription(collect.getTitle());
//                } else {
//                    collect.setDescription(result.get("description"));
//                }
//                collect.setRemark(entry.getValue());
//                collect.setFavoritesId(favoritesId);
//                collect.setIsDelete(IsDelete.NO);
////				collect.setLogoUrl(getMap(entry.getKey()));  //避免后台一直加载logo  2020年1月1日18:19:33
//                if (CollectType.PRIVATE.toString().equals(type)) {
//                    collect.setType(CollectType.PRIVATE);
//                } else {
//                    collect.setType(CollectType.PUBLIC);
//                }
//                collect.setUrl(entry.getKey());
//                collect.setUserId(userId);
//                collect.setCreateTime(DateUtils.getCurrentTime());
//                collect.setLastModifyTime(DateUtils.getCurrentTime());
//                List<Collect> list = collectRepository.findByFavoritesIdAndUrlAndUserIdAndIsDelete(favoritesId, entry.getKey(), userId, IsDelete.NO);
//                if (null != list && list.size() > 0) {
//                    logger.info("收藏夹：" + favoritesId + "中已经存在：" + entry.getKey() + "这个文章，不在进行导入操作");
//                    continue;
//                }
//                collects.add(collect);
//                favoritesService.countFavorites(favoritesId);
//            }
//            collectRepository.saveAll(collects);
//        } catch (Exception e) {
//            logger.error("导入存储异常：", e);
//        }
//
//    }


    /**
     * 导入收藏文章
     */
    @Override
    public void importHtml(Map<String, String> map, Long favoritesId, Long userId, String type) {
        List<Collect> collects = new ArrayList<>();
        List<String> strList = new ArrayList<>();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            strList.add(entry.getKey());
        }
        boolean flag = false;
        List<String> list = collectRepository.findByFavoritesIdAndUrlAndUserIdAndIsDelete1(favoritesId, strList, userId, IsDelete.NO);
        try {
            flag = false;
            for (Map.Entry<String, String> entry : map.entrySet()) {
//				Map<String, String> result = HtmlUtil.getCollectFromUrl(entry.getKey());
                for (String str : list) {
                    if (entry.getKey().equals(str)) {
                        logger.info("收藏夹：" + favoritesId + "中已经存在：" + entry.getKey() + "这个文章，不在进行导入操作");
                        flag = true;
                    }
                }
                if (flag) {
                    continue;
                }
                URL url = new URL(entry.getKey());
                Collect collect = new Collect();
//				collect.setCharset(result.get("charset"));
                collect.setUrl(entry.getKey());
                collect.setTitle(entry.getValue());
                collect.setFavoritesId(favoritesId);
                collect.setIsDelete(IsDelete.NO);
                collect.setLogoUrl(Const.URL_LOGO_API + url.getHost());  //避免后台一直加载logo  2020年1月1日18:19:33
                if (CollectType.PRIVATE.toString().equals(type)) {
                    collect.setType(CollectType.PRIVATE);
                } else {
                    collect.setType(CollectType.PUBLIC);
                }
                collect.setUserId(userId);
                collect.setCreateTime(DateUtils.getCurrentTime());
                collect.setLastModifyTime(DateUtils.getCurrentTime());
                collects.add(collect);
            }
            collectRepository.saveAll(collects);
            favoritesService.countFavorites(favoritesId);
        } catch (Exception e) {
            logger.error("导入存储异常：", e);
        }

    }

    /**
     * 导出到html文件
     *
     * @param favoritesId
     */
    @Override
    public StringBuilder exportToHtml(long favoritesId) {
        try {
            Favorites favorites = favoritesRepository.findById(favoritesId);
            StringBuilder sb = new StringBuilder();
            List<Collect> collects = collectRepository.findByFavoritesIdAndIsDelete(favoritesId, IsDelete.NO);
            StringBuilder sbc = new StringBuilder();
            for (Collect collect : collects) {
                sbc.append("<DT><A HREF=\"" + collect.getUrl() + "\" TARGET=\"_blank\">" + collect.getTitle() + "</A></DT>");
            }
            sb.append("<DL><P></P><DT><H3>" + favorites.getName() + "</H3><DL><P></P>" + sbc + "</DL></DT></DL>");
            return sb;
        } catch (Exception e) {
            logger.error("异常：", e);
        }
        return null;
    }


    /**
     * @return
     * @author Lyoko
     * @date 2016年9月8日
     */
    private Long createfavorites(Collect collect) {
        Favorites favorites = favoritesRepository.findByUserIdAndName(collect.getUserId(), collect.getNewFavorites());
        if (null == favorites) {
            favorites = favoritesService.saveFavorites(collect);
        }
        return favorites.getId();

    }


    /**
     * @param collect
     * @通知好友
     * @author Lyoko
     * @date 2019年11月24日
     */
    private void noticeFriends(Collect collect) {
        if (StringUtils.isNotBlank(collect.getRemark()) && collect.getRemark().indexOf("@") > -1) {
            List<String> atUsers = StringUtil.getAtUser(collect.getRemark());
            for (String str : atUsers) {
                logger.info("用户名：" + str);
                User user = userRepository.findByUserName(str);
                if (null != user) {
                    // 保存消息通知
                    noticeService.saveNotice(String.valueOf(collect.getId()), "at", user.getId(), null);
                } else {
                    logger.info("为找到匹配：" + str + "的用户.");
                }
            }
        }
    }


    @Override
    public void like(Long userId, long id) {
        Praise praise = praiseRepository.findByUserIdAndCollectId(userId, id);
        if (praise == null) {
            Praise newPraise = new Praise();
            newPraise.setUserId(userId);
            newPraise.setCollectId(id);
            newPraise.setCreateTime(DateUtils.getCurrentTime());
            praiseRepository.save(newPraise);
            // 保存消息通知
            Collect collect = collectRepository.findById(id);
            if (null != collect) {
                noticeService.saveNotice(String.valueOf(id), "praise", collect.getUserId(), String.valueOf(newPraise.getId()));
            }
        } else if (praise.getUserId().equals(userId)) {
            praiseRepository.deleteById(praise.getId());
        }
    }

    @Override
    public String getContent(String url) {
        try {
            Document doc = null;
            doc = Jsoup.connect(url).get();
            Whitelist whitelist = new Whitelist();
            String content = doc.getAllElements().html();
            if (null != content && !content.equals("")) {
                content = Jsoup.clean(content, whitelist).replaceAll(" ", "");
                logger.info("【获取内容成功】网址:" + url + "\n 获取的内容为:" + content);
            }

            return content;
        } catch (IOException e) {
            logger.error("获取网页内容错误");
            e.printStackTrace();
            return null;
        }

    }

    /**
     * 收藏文章默认点赞
     *
     * @param userId
     * @param id
     */
    public void collectLike(Long userId, long id) {
        Praise praise = praiseRepository.findByUserIdAndCollectId(userId, id);
        if (praise == null) {
            Praise newPraise = new Praise();
            newPraise.setUserId(userId);
            newPraise.setCollectId(id);
            newPraise.setCreateTime(DateUtils.getCurrentTime());
            praiseRepository.save(newPraise);
            // 保存消息通知
            Collect collect = collectRepository.findById(id);
            if (null != collect) {
                noticeService.saveNotice(String.valueOf(id), "praise", collect.getUserId(), String.valueOf(newPraise.getId()));
            }
        }
    }

}