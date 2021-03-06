package com.jielong.core.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.jielong.core.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jielong.base.util.Constants;
import com.jielong.base.util.ErrorCode;
import com.jielong.base.util.Utils;
import com.jielong.core.beans.PickBean;
import com.jielong.core.beans.PickCountBean;
import com.jielong.core.beans.ResponseBean;
import com.jielong.core.beans.SignBean;
import com.jielong.core.beans.SignPickBean;
import com.jielong.core.dao.CommonDao;
import com.jielong.core.dao.GoodsMapper;
import com.jielong.core.dao.JielongMapper;
import com.jielong.core.dao.OrderGoodsMapper;
import com.jielong.core.dao.OrderMapper;
import com.jielong.core.domain.Goods;
import com.jielong.core.domain.Order;
import com.jielong.core.domain.OrderGoods;
import com.jielong.core.domain.UserAddress;
import com.jielong.core.domain.UserInfo;
import com.jielong.core.domain.UserMessage;

@Service
public class OrderServiceImpl implements OrderService {

	@Autowired
	CommonDao commonDao;
	@Autowired
	OrderMapper orderMapper;
	@Autowired
	OrderGoodsService orderGoodsService;
	@Autowired
	JielongService jielongService;
	@Autowired
	UserMessageService userMessageService;
	@Autowired
	UserAddressService userAddressService;
	@Autowired
	UserInfoService userInfoService;

	@Autowired
	GoodsMapper goodsMapper;
	@Autowired
	JielongMapper jielongMapper;

	@Autowired
	OrderGoodsMapper orderGoodsMapper;
	
	@Autowired
	WxPayServiceImpl wxPayService;
	
	@Autowired
	UserService userService;

	@Autowired
	DistributionService distributionService;
	
	/**
	 * 不含支付时插入订单
	 */
	@Transactional
	@Override
	public ResponseBean<Integer> insert(Order order) {
		ResponseBean<Integer> responseBean = new ResponseBean<>();

		// 订单内商品列表
		List<OrderGoods> orderGoodsList = order.getOrderGoods();

		String orderNum = Utils.createFileName();
		// 订单编号
		order.setOrderNum(orderNum);
		// 订单状态 2下单成功，待提货
		order.setState(2);
		// 订单总金额
		BigDecimal sumMoney = new BigDecimal(0);
		if (orderGoodsList != null && orderGoodsList.size() > 0) {
			for (int j = 0; j < orderGoodsList.size(); j++) {
				BigDecimal price = orderGoodsList.get(j).getMoney();
				Integer sum = orderGoodsList.get(j).getSum();
				BigDecimal tempMOney = price.multiply(new BigDecimal(sum));

				sumMoney = sumMoney.add(tempMOney);
			}
		}

		order.setSumMoney(sumMoney);

		orderMapper.insertSelective(order);
		Integer orderId = commonDao.getLastId();

		StringBuilder goodsInfo = new StringBuilder();
		if (orderGoodsList != null && orderGoodsList.size() > 0) {
			for (int i = 0; i < orderGoodsList.size(); i++) {
				OrderGoods orderGoods = orderGoodsList.get(i);
				orderGoods.setOrderId(orderId);
				orderGoodsService.insert(orderGoods);
				// 商品信息
				Goods goods = goodsMapper.selectByPrimaryKey(orderGoods.getGoodsId());
				
				goodsInfo.append(goods.getName()).append(orderGoods.getSum()).append(goods.getSpecification());
				// 减少对应商品的库存
				goodsMapper.updateRepertory(orderGoods.getGoodsId(), orderGoods.getSum());
			}
		}

		// 下单之后，更新接龙参与人数、参与金额等信息
		jielongService.updateJoin(order.getJielongId(), sumMoney);
		
		//给用户发送消息
		sendMessage(goodsInfo.toString(), order);

		responseBean.setData(1);
		return responseBean;
	}

	/**
	 * 含支付时插入订单逻辑
	 */
	@Transactional
	@Override
	public ResponseBean<Map<String, String>> insertWithPay(Order order) {
		
		ResponseBean<Map<String, String>> responseBean = new ResponseBean<>();

		// 订单内商品列表
		List<OrderGoods> orderGoodsList = order.getOrderGoods();

		String orderNum = Utils.createFileName();
		// 订单编号
		order.setOrderNum(orderNum);
		// 订单状态 1下单成功，待支付
		order.setState(1);
		// 订单总金额
		BigDecimal sumMoney = new BigDecimal(0);
		
		if (orderGoodsList != null && orderGoodsList.size() > 0) {
			for (OrderGoods orderGoods : orderGoodsList) {
		        //检查一下该商品的库存
				Integer repertory=goodsMapper.selectByPrimaryKey(orderGoods.getGoodsId()).getRepertory();
				if (repertory<=0) {
					//库存不足
					responseBean.setErrorCode(ErrorCode.REPERTORY_EXCEPTION);
					responseBean.setErrorMessage("有商品库存不足");
					return responseBean;
				}
				BigDecimal price = orderGoods.getMoney();
				Integer sum = orderGoods.getSum();
				BigDecimal tempMOney = price.multiply(new BigDecimal(sum));
				sumMoney = sumMoney.add(tempMOney);
			}
		}

		order.setSumMoney(sumMoney);

		orderMapper.insertSelective(order);
		Integer orderId = commonDao.getLastId();

		//StringBuilder goodsInfo = new StringBuilder();
		if (orderGoodsList != null && orderGoodsList.size() > 0) {
			for (int i = 0; i < orderGoodsList.size(); i++) {
				OrderGoods orderGoods = orderGoodsList.get(i);
				orderGoods.setOrderId(orderId);
				orderGoodsService.insert(orderGoods);
			}
		}
		
		//调用微信支付
		//1、获取用户的openId
		String openId=userService.selectById(order.getUserId()).getOpenId();
		String goodsDesc="VanMart-景点门票";
		//订单总金额，要换成单位  分
		int totalFee=sumMoney.multiply(new BigDecimal(100)).intValue();
		//TODO:测试时用1分
		//int totalFee=1;
		Map<String, String> map=wxPayService.wxPay(openId, goodsDesc, orderNum,totalFee,0);
		if (null!=map) {
			responseBean.setData(map);
		}else {
			responseBean.setErrorCode(ErrorCode.PAY_EXCEPTION);
			responseBean.setErrorMessage("支付发生错误");
		}
		
        //商品库存、参与人数、参与金额等更新信息要在微信支付的异步通知url中调用
		
		return responseBean;
	}
	
	/**
	 * 更新订单信息(商品库存、接龙参与人数、参与金额等信息)，微信支付异步通知url中调用
	 * @param order 订单
	 */
	@Override
	@Transactional
	public void updateOrder(Order order) {		
		StringBuilder goodsInfo = new StringBuilder();
	   	//获取订单内包含的商品信息，减少其库存
		List<OrderGoods> goodsList=orderGoodsService.selectByOrderId(order.getId());
		for (OrderGoods orderGoods : goodsList) {
			// 减少对应商品的库存
			goodsMapper.updateRepertory(orderGoods.getGoodsId(), orderGoods.getSum());
			// 商品信息
			Goods goods = goodsMapper.selectByPrimaryKey(orderGoods.getGoodsId());				
			goodsInfo.append(goods.getName()).append(orderGoods.getSum()).append(goods.getSpecification());
		}
		
		//更新订单状态 : 待支付->已支付(1->2)
		Order newOrder=new Order();
		newOrder.setId(order.getId());
		newOrder.setState(2);
		orderMapper.updateByPrimaryKeySelective(newOrder);
		
		
		// 下单之后，更新接龙参与人数、参与金额等信息
		jielongService.updateJoin(order.getJielongId(), order.getSumMoney());


		//更新分销
		distributionService.insert(order);
				
		//给用户发送消息
		sendMessage(goodsInfo.toString(), order);			
		
		
		
		
	}
	
	
	
	public void sendMessage(String goodsInfo,Order order) {
		//获取Jielong主题
		String topic=jielongMapper.selectTopic(order.getJielongId());
		
		//获取下单地址				
		String addressInfo=userAddressService.selectById(order.getAddressId()).getData().getDetail().replace("***", "  提货时间");
				
		StringBuilder message=new StringBuilder();
		message.append("恭喜您成功下单：\"").append(topic).append("\"的\"").append(goodsInfo)
		       .append("\",").append("您选择了于\"").append(addressInfo).append("\"取货，").append("请您牢记！如有问题，可以随时与发起人员沟通。")
		       .append("订单详情请前往\"我的\"-\"我参与的Mart\"进行查看。");
		// 下单之后给用户发送消息
		UserMessage userMessage = new UserMessage();
		userMessage.setUserId(order.getUserId());
		userMessage.setTitle("下单成功通知！");
		userMessage.setMessage(message.toString());
		userMessageService.insert(userMessage);
		
		
	}

	// 根据顾客id查询订单(参与的接龙)
	@Transactional
	@Override
	public ResponseBean<List<Order>> selectByCustomerId(Integer userId) {
		ResponseBean<List<Order>> responseBean = new ResponseBean<List<Order>>();
		List<Order> orderList = orderMapper.selectByUserId(userId);
		if (orderList != null && orderList.size() > 0) {

			for (Order order : orderList) {
				// Jielong主题
				String topic = jielongMapper.selectTopic(order.getJielongId());
				order.setJielongTopic(topic);
				// 提货地址信息
				Integer addressId = order.getAddressId();
				UserAddress address = userAddressService.selectById(addressId).getData();
				order.setUserAddress(address);
				// 用户信息
				Integer clientId = order.getUserId();
				UserInfo userInfo = userInfoService.selectByUserId(clientId).getData();
				order.setUserInfo(userInfo);
				// 订单商品信息
				List<OrderGoods> orderGoodsList = orderGoodsService.selectByOrderId(order.getId());

				if (orderGoodsList != null && orderGoodsList.size() > 0) {
					for (OrderGoods orderGoods : orderGoodsList) {
						Integer goodsId = orderGoods.getGoodsId();
						Goods goods = goodsMapper.selectByPrimaryKey(goodsId);
						orderGoods.setGoods(goods);
					}

					order.setOrderGoods(orderGoodsList);
				}

			}
		}

		responseBean.setData(orderList);
		return responseBean;
	}

	/**
	 * 根据发布者id查询订单
	 */
	@Transactional
	@Override
	public ResponseBean<List<Order>> selectByPublisherId(Integer userId) {
		ResponseBean<List<Order>> responseBean = new ResponseBean<List<Order>>();
		List<Order> orderList = orderMapper.selectByPublisherId(userId);
		if (orderList != null && orderList.size() > 0) {
			for (Order order : orderList) {
				// Jielong主题
				String topic = jielongMapper.selectTopic(order.getJielongId());
				order.setJielongTopic(topic);
				// 提货地址信息
				Integer addressId = order.getAddressId();
				UserAddress address = userAddressService.selectById(addressId).getData();
				order.setUserAddress(address);
				// 用户信息
				Integer clientId = order.getUserId();
				UserInfo userInfo = userInfoService.selectByUserId(clientId).getData();
				order.setUserInfo(userInfo);
				// 订单商品信息
				List<OrderGoods> orderGoodsList = orderGoodsService.selectByOrderId(order.getId());

				if (orderGoodsList != null && orderGoodsList.size() > 0) {
					for (OrderGoods orderGoods : orderGoodsList) {
						Integer goodsId = orderGoods.getGoodsId();
						Goods goods = goodsMapper.selectByPrimaryKey(goodsId);
						orderGoods.setGoods(goods);
					}

					order.setOrderGoods(orderGoodsList);
				}

			}
		}

		responseBean.setData(orderList);
		return responseBean;
	}

	@Override
	public ResponseBean<List<Order>> selectByJielongId(Integer jielongId) {
		ResponseBean<List<Order>> responseBean = new ResponseBean<List<Order>>();

		List<Order> orderList = orderMapper.selectByJielongId(jielongId);
		if (orderList != null && orderList.size() > 0) {

			for (Order order : orderList) {

				// 提货地址信息
				Integer addressId = order.getAddressId();
				UserAddress address = userAddressService.selectById(addressId).getData();
				order.setUserAddress(address);
				// 用户信息
				Integer clientId = order.getUserId();
				UserInfo userInfo = userInfoService.selectByUserId(clientId).getData();
				order.setUserInfo(userInfo);
				// 订单商品信息
				List<OrderGoods> orderGoodsList = orderGoodsService.selectByOrderId(order.getId());

				if (orderGoodsList != null && orderGoodsList.size() > 0) {
					for (OrderGoods orderGoods : orderGoodsList) {
						Integer goodsId = orderGoods.getGoodsId();
						Goods goods = goodsMapper.selectByPrimaryKey(goodsId);
						orderGoods.setGoods(goods);
					}

					order.setOrderGoods(orderGoodsList);
				} // end if

			}
		} // end if

		responseBean.setData(orderList);
		return responseBean;
	}

	/**
	 * 设置自提标记
	 */
	@Override
	public ResponseBean<Integer> signPick(SignPickBean signPickBean) {
		ResponseBean<Integer> responseBean = new ResponseBean<Integer>();
		Integer result = 0;
		List<SignBean> signBeanList = signPickBean.getOrderNumList();
		if (signBeanList != null && signBeanList.size() > 0) {
			for (SignBean signBean : signBeanList) {
				result += orderMapper.signPick(signBean);

			}

		}

		responseBean.setData(result);
		return responseBean;

	}

	@Override
	public Order selectById(Integer id) {
		Order order = orderMapper.selectByPrimaryKey(id);
		if (order != null) {
			// 用户信息
			Integer clientId = order.getUserId();
			UserInfo userInfo = userInfoService.selectByUserId(clientId).getData();
			order.setUserInfo(userInfo);
			// 提货地址信息
			Integer addressId = order.getAddressId();
			UserAddress address = userAddressService.selectById(addressId).getData();
			order.setUserAddress(address);
		}

		return order;
	}

	/**
	 * 自提统计
	 */
	@Override
	public ResponseBean<List<PickCountBean>> countPick(Integer jielongId) {

		List<PickCountBean> pickCountBeanList = new ArrayList<PickCountBean>();

		// 1、首先根据jielongId查询所有商品
		List<Integer> goodIds = goodsMapper.selectIdsByJielongId(jielongId);
		for (Integer goodsId : goodIds) {

			PickCountBean pickCountBean = new PickCountBean();
			Goods goods = goodsMapper.selectByPrimaryKey(goodsId);
			pickCountBean.setGoods(goods);
			// 参与人数
			Integer joinPeopleSum = 0;
			// 已售数量
			Integer sellSum = 0;
			// 入账总额
			BigDecimal moneySum = new BigDecimal(0);
			// 2、用商品id去订单商品列表查询所有订单
			List<OrderGoods> orderGoodsList = orderGoodsMapper.selectByGoodsId(goodsId);

			if (orderGoodsList != null && orderGoodsList.size() > 0) {
				List<PickBean> pickBeans = new ArrayList<PickBean>();
				for (OrderGoods orderGoods : orderGoodsList) {

					joinPeopleSum += 1;
					sellSum += orderGoods.getSum();
					BigDecimal totalMoney = orderGoods.getMoney().multiply(new BigDecimal(orderGoods.getSum()));
					moneySum = moneySum.add(totalMoney);

					PickBean pickBean = new PickBean();
					pickBean.setCreatedAt(orderGoods.getCreatedAt());
					pickBean.setGoodsSum(orderGoods.getSum());
					pickBean.setPrice(orderGoods.getMoney());
					// 用订单id去订单表里查询
					Order order = this.selectById(orderGoods.getOrderId());
					pickBean.setPhoneNumber(order.getUserPhone());
					pickBean.setUserName(order.getUserName());
					pickBean.setRemark(order.getRemark());
					pickBean.setUserInfo(order.getUserInfo());
					pickBean.setUserAddress(order.getUserAddress());
					pickBeans.add(pickBean);
				}
				pickCountBean.setPickBeans(pickBeans);

			}

			pickCountBean.setJoinPeopleSum(joinPeopleSum);
			pickCountBean.setMoneySum(moneySum);
			pickCountBean.setSellSum(sellSum);
			pickCountBeanList.add(pickCountBean);
		}
		return new ResponseBean<>(pickCountBeanList);
	}

	/**
	 * 取消订单
	 */
	@Transactional
	@Override
	public ResponseBean<Integer> cancelOrder(Integer orderId) {

		Order order = new Order();
		order.setId(orderId);
		// state:4 订单取消
		order.setState(4);
		Integer result = orderMapper.updateByPrimaryKeySelective(order);

		Order updateOrder = orderMapper.selectByPrimaryKey(orderId);
		// 减少参与人数和接龙金额
		jielongMapper.reduceJoin(updateOrder.getJielongId(), updateOrder.getSumMoney());
		// 订单内商品列表
		List<OrderGoods> orderGoodsList = orderGoodsMapper.selectByOrderId(orderId);
		if (orderGoodsList != null && orderGoodsList.size() > 0) {
			for (OrderGoods orderGoods : orderGoodsList) {
				// 增加库存
				goodsMapper.addRepertory(orderGoods.getGoodsId(), orderGoods.getSum());
			}

		}
		ResponseBean<Integer> responseBean = new ResponseBean<Integer>(result);
		return responseBean;
	}

}
