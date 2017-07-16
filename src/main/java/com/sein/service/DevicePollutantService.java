package com.sein.service;

import com.sein.dao.DeviceDAO;
import com.sein.dao.PollutantDAO;
import com.sein.enums.ResultEnum;
import com.sein.pojo.dto.*;
import com.sein.pojo.po.Device;
import com.sein.pojo.po.DisplayConfig;
import com.sein.pojo.po.Pollutant;
import com.sein.service.utils.DevicePollutantUtil;
import com.sein.service.utils.PollutantUtil;
import com.sein.service.utils.StringUtil;
import com.sein.service.utils.TransformGPSUtil;
import com.sein.utils.AQIUtil;
import com.sein.utils.DateUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tk.mybatis.mapper.entity.Example;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.TreeSet;

/**
 * Created by ldb on 2017/7/13.
 * 设备封装浓度的Service类
 */
@Service
public class DevicePollutantService {

    Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private DeviceDAO deviceDAO;

    @Autowired
    private PollutantDAO pollutantDAO;

    @Value("${POLLUTANT_TYPE_BASE}")
    private String POLLUTANT_TYPE_BASE;

    /**
     * list页面 获取设备列表以及相应的最新污染物
     *
     * @param displayConfig
     * @return
     */
    public List<DevicePollutant> listDevicePollutant(DisplayConfig displayConfig, String cityName) {
        List<DevicePollutant> devicePollutantList = new ArrayList<>();

        Example example = new Example(Device.class);

        //cityName进行模糊搜索
        if (!StringUtils.isEmpty(cityName)) {
            example.createCriteria().andLike("cityName", StringUtil.formatSQLLikeStr(cityName));
        }
        //查询设备列表
        example.createCriteria().andEqualTo("accountId", displayConfig.getId());
        List<Device> deviceList = deviceDAO.selectByExample(example);

        for (Device device : deviceList) {
            DevicePollutant devicePollutant = new DevicePollutant();

            //获取最新后缀参数拼接表格参数
            HashMap<String, Object> param = new HashMap<>();
            String tablePostfix = PollutantUtil.getNewestPostfix(displayConfig);
            param.put("pollutantTable", device.getPollutantTable() + tablePostfix);

            //查询最新一条浓度数据根据数据获取ItemList
            Pollutant pollutant = pollutantDAO.getPollutant(param);
            //如果为空，则设置一个时间，避免异常
            if (pollutant == null) {
                pollutant = new Pollutant();
                pollutant.setTime(DateUtil.getCurrentDateStr());
            }
            List<PollutantItem> pollutantItemList = PollutantUtil.getPollutantItemList(displayConfig, pollutant);

            //封装devicePollutant参数并添加
            devicePollutant.setDevice(device);
            devicePollutant.setPollutantItemList(pollutantItemList);
            //设置状态
            DevicePollutantUtil.setStatus(devicePollutant, pollutant.getTime());
            devicePollutantList.add(devicePollutant);
        }
        return devicePollutantList;
    }

    /**
     * 封装Map需要的信息
     *
     * @param devicePollutantList
     * @return
     */
    public void setDevicePollutantMap(List<DevicePollutant> devicePollutantList, DisplayConfig displayConfig) {
        for (DevicePollutant devicePollutant : devicePollutantList) {
            Device device = devicePollutant.getDevice();

            //转换GPS并添加
            TransformGPSResult result = TransformGPSUtil.transformBD(device.getLongitude(), device.getLatitude());
            GPS gps = result.getResult().get(0);
            if (gps != null) {
                device.setLongitude(gps.getX());
                device.setLatitude(gps.getY());
            }
            //计算并设置AQI
            Double AQI = null;
            try {
                AQI = AQIUtil.getAQI(devicePollutant.getPollutantItemList());
            } catch (Exception e) {
                AQI = 0.0;
            }
            devicePollutant.setAQI((int) Math.floor(AQI) + "");
            devicePollutant.setDevice(device);
        }

    }

    /**
     * detail页，根据id获取设备及对应浓度
     *
     * @param displayConfig
     * @param id
     * @return
     */
    public DevicePollutant getDevicePollutantById(DisplayConfig displayConfig, Integer id) {
        DevicePollutant devicePollutant = new DevicePollutant();

        Device device = deviceDAO.selectByPrimaryKey(id);

        //获取最新后缀参数拼接表格参数
        HashMap<String, Object> param = new HashMap<>();
        String tablePostfix = PollutantUtil.getNewestPostfix(displayConfig);
        param.put("pollutantTable", device.getPollutantTable() + tablePostfix);

        //查询最新一条浓度数据根据数据获取ItemList
        Pollutant pollutant = pollutantDAO.getPollutant(param);
        //如果为空，则设置一个时间，避免异常
        if (pollutant == null) {
            pollutant = new Pollutant();
            pollutant.setTime(DateUtil.getCurrentDateStr());
        }
        List<PollutantItem> pollutantItemList = PollutantUtil.getPollutantItemList(displayConfig, pollutant);
        //计算并获取AQI
        Double AQI = null;
        try {
            AQI = AQIUtil.getAQI(pollutantItemList);
        } catch (Exception e) {
            AQI = 0.0;
        }
        //封装devicePollutant参数并添加
        devicePollutant.setAQI((int) Math.floor(AQI) + "");
        devicePollutant.setPollutantItemList(pollutantItemList);
        //设置状态
        DevicePollutantUtil.setStatus(devicePollutant, pollutant.getTime());
        devicePollutant.setDevice(device);
        return devicePollutant;
    }

    /**
     * detail页的折线图部分，获取折线图数据
     *
     * @param id
     * @param pollutantType
     * @param startTime
     * @param endTime
     * @param interval
     * @return
     */
    public List<PollutantChartItem> listPollutantChart(Integer id, String pollutantType, String startTime, String endTime, String interval) {
        //封装参数
        HashMap<String, Object> pollutantParam = new HashMap<>();
        Device device = deviceDAO.selectByPrimaryKey(id);
        pollutantParam.put("pollutantTable", device.getPollutantTable() + interval);
        //添加DateTime
        pollutantParam.put("pollutantType", this.POLLUTANT_TYPE_BASE + pollutantType);
        pollutantParam.put("startTime", startTime);
        pollutantParam.put("endTime", endTime);
        //查询满足参数的浓度列表
        List<Pollutant> pollutantList = pollutantDAO.listPollutant(pollutantParam);

        List<PollutantChartItem> pollutantChartList = PollutantUtil.getPollutantChartList(pollutantType, pollutantList, null);
        return pollutantChartList;
    }

    /**
     * compare页折线图部分，获取折线图数据
     *
     * @param accountId
     * @param pollutantType
     * @param interval
     * @param startTime
     * @param endTime
     * @return
     */
    public List<DevicePollutantChart> listDevicePollutantChart(Integer accountId, String pollutantType, String interval, String startTime, String endTime) {
        List<DevicePollutantChart> devicePollutantChartList = new ArrayList<>();

        //查询所有的设备
        Example example = new Example(Device.class);
        example.createCriteria().andEqualTo("accountId", accountId);
        List<Device> deviceList = deviceDAO.selectByExample(example);

        //设置通用查询参数
        HashMap<String, Object> param = new HashMap<>();
        param.put("startTime", startTime);
        param.put("endTime", endTime);
        param.put("pollutantType", this.POLLUTANT_TYPE_BASE + pollutantType);

        //横坐标，目的是为了获取最大横坐标
        TreeSet<String> xaisMaxSet = new TreeSet<>();

        //封装所有设备折线图
        for (Device device : deviceList) {
            DevicePollutantChart devicePollutantChart = new DevicePollutantChart();

            //设置设备对应表名
            param.put("pollutantTable", device.getPollutantTable() + interval);
            List<Pollutant> pollutantList = pollutantDAO.listPollutant(param);
            //获取单个浓度折线图
            List<PollutantChartItem> pollutantChartList = PollutantUtil.getPollutantChartList(pollutantType, pollutantList, xaisMaxSet);
            //封装参数
            devicePollutantChart.setDevice(device);
            devicePollutantChart.setPollutantChartItemList(pollutantChartList);
            devicePollutantChartList.add(devicePollutantChart);
        }

        //填充横坐标，目的是为了让多段浓度数据的横坐标一致
        for (DevicePollutantChart dpc : devicePollutantChartList) {
            List<PollutantChartItem> PollutantChartItemList = dpc.getPollutantChartItemList();
            //封装timeList
            List<String> timeList = new ArrayList<>();
            for (PollutantChartItem item : PollutantChartItemList) {
                timeList.add(item.getTime());
            }
            //遍历最大横坐标
            int index = 0;
            for (String s : xaisMaxSet) {
                if (!timeList.contains(s)) {
                    //如果不存在则将时间设置进去
                    PollutantChartItem pcItem = new PollutantChartItem();
                    pcItem.setTime(s);
                    //在i处添加一个横坐标
                    PollutantChartItemList.add(index, pcItem);
                }
                index++;
            }

        }
        return devicePollutantChartList;
    }


    /**
     * 从浓度表获取最新的GPS位置信息
     * @param displayConfig
     * @param device
     * @return
     */
    public Result getDeviceNewestGPS(DisplayConfig displayConfig,Device device){
        GPS gps=new GPS();
        //封装pollutantTable参数
        String interval= PollutantUtil.getNewestPostfix(displayConfig);
        String pollutantTable=device.getPollutantTable()+interval;
        //执行查询是否存在GPS信息
        Integer isExist = pollutantDAO.isExistGPSColumn(pollutantTable);
        if(isExist>0){
            Pollutant deviceNewestGPS = pollutantDAO.getDeviceNewestGPS(pollutantTable);
            //没有查询到GPS信息
            if(deviceNewestGPS==null){
                return Result.isNotOK(ResultEnum.GPS_ERROR.getInfo());
            }else{
                //查询到GPS，封装返回
                gps.setX(deviceNewestGPS.getLongitude());
                gps.setY(deviceNewestGPS.getLatitude());
                return Result.isOK(gps);
            }
        }else{
            return Result.isNotOK(ResultEnum.GPS_COLUMN_NULL.getInfo());
        }

    }

}
