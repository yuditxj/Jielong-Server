package com.jielong.core.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import com.jielong.Application;
import com.jielong.core.dao.UserMessageMapper;
import com.jielong.core.domain.UserMessage;

@RunWith(SpringJUnit4ClassRunner.class)
@SpringBootTest(classes = Application.class)
public class JielongServiceTest {
	@Autowired
	JielongService jielongService;
//	@Autowired UserMessageService userMessageService;
	@Autowired
	UserMessageMapper userMessageMapper;
	
	@Test
	public void  testUpdateJoin() {	 
	  jielongService.updateJoin(12,new BigDecimal(200));
	}
	
	@Test
	public void testInsertBatch() {
		UserMessage userMessage=new UserMessage();
		userMessage.setTitle("测试消息");
		userMessage.setMessage("测试消息");
		userMessage.setIsRead(0);
		
		List<Integer> userIdList=new ArrayList<>();
		userIdList.add(0);
		userIdList.add(1);
		userIdList.add(2);
		userMessage.setUserIdList(userIdList);
		Integer result=userMessageMapper.insertBatch(userMessage);
		junit.framework.Assert.assertEquals(3, result.intValue());
	     
	}

}