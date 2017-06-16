package com.alucn.casemanager.client.thread;

import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.util.Date;
import java.util.List;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.log4j.Logger;

import com.alucn.casemanager.client.common.util.AutoIncrement;
import com.alucn.casemanager.client.common.util.CaseCache;
import com.alucn.casemanager.client.common.util.CommonsUtil;
import com.alucn.casemanager.client.common.util.Fiforeader;
import com.alucn.casemanager.client.common.util.Fifowriter;
import com.alucn.casemanager.client.common.util.ParamUtil;
import com.alucn.casemanager.client.common.util.PythonUtil;
import com.alucn.casemanager.client.constants.Constants;
import com.alucn.casemanager.client.exception.CaseInitListenerException;
import com.alucn.casemanager.client.exception.CaseParamIncompletedException;
import com.alucn.casemanager.client.exception.CaseRefreshConfCacheException;
import com.alucn.casemanager.client.exception.CaseSocketConnectionException;
import com.alucn.casemanager.client.listener.InitListener;
import com.alucn.casemanager.client.model.Configuration;
import com.alucn.casemanager.client.model.SocketInfo;
/**
 * Daemon thread
 * @author wanghaiqi
 *
 */
public class CaseMonitorThread implements Runnable{
	
	public static Logger logger = Logger.getLogger(CaseMonitorThread.class);

	private static Thread hotCache;
	private static Thread healthChecker;
	private static Thread pythonMonitor;
	
	private static Boolean isStopHotCache;
	private static Boolean isStopHeathyCheck;
	private static Boolean isStopPythonMonitor;
	
	private String cacheCaseListUUID = "";
	private static JSONObject caseStatusInfo = null;
	private JSONObject messagePre = null;
	private SocketInfo socketInfo;
	
	private boolean isSendCaseStatus = true;
	private boolean isSendCommandStatus = true;
	private boolean isOrNotStartedPy = false;
	private boolean isOrNotRecon = false;
	
	private String pythonName = "";
	private String pythonPath = "";
	private String caseListPath = "";
	private String caseListName = "";
	private String caseStatusPath = "";
	private String casespartdbPath = "";
	private String caseHandleNum = "";
	
	@SuppressWarnings({ "static-access" })
	@Override
	public void run() {
		try {
			initParams();
			//To determine whether the first call, or case information on the hot load thread and the health check thread all stop running
			if(hotCache==null&&healthChecker==null&&pythonMonitor==null){
				logger.info("[Initializing health detection and case information for hot load thread opening]");
				//Start refresh server list thread
				hotCache = new Thread(new HotCache());
				isStopHotCache = true;
				hotCache.start();
				if(hotCache.isAlive()){
					logger.info("[Hot load thread has been started]");
				}else{
					throw new CaseInitListenerException("[Failed to start the hot load thread]");
				}
				//Start health detection thread
				healthChecker = new Thread(new HealthChecker());
				isStopHeathyCheck = true;
				healthChecker.start();
				if(healthChecker.isAlive()){
					logger.info("[Health monitoring thread has been started]");
				}else{
					throw new CaseInitListenerException("[Failed to start the health test thread]");
				}
				//Start python monitor thread
				pythonMonitor = new Thread(new PythonMonitor());
				isStopPythonMonitor = true;
				pythonMonitor.start();
				if(pythonMonitor.isAlive()){
					logger.info("[python monitor thread has been started]");
				}else{
					throw new CaseInitListenerException("[Failed to start the python monitor thread]");
				}
				logger.info("[Initializes the health check and case information for the hot load and python monitor thread ends]");
			}
			//Guardian thread main logic, regular detection of case information and the thread of the heat load test thread is running properly
			while(true){
				if(hotCache==null||!hotCache.isAlive()){
					hotCache = new Thread(new HotCache());
					isStopHotCache = true;
					hotCache.start();
				}
				if(healthChecker==null||!healthChecker.isAlive()){
					healthChecker = new Thread(new HealthChecker());
					isStopHeathyCheck = true;
					healthChecker.start();
				}
				if(pythonMonitor==null||!pythonMonitor.isAlive()){
					pythonMonitor = new Thread(new PythonMonitor());
					isStopPythonMonitor = true;
					pythonMonitor.start();
				}
				try {
					long daemonTimeBreak = Configuration.getConfiguration().getRefreshTimeBreak();
					Thread.currentThread().sleep(daemonTimeBreak);
				} catch (InterruptedException e) {
					logger.error(Thread.currentThread().getName()+" Thread sleep exception ",e);
					e.printStackTrace();
				}
			}
		} catch (CaseInitListenerException e) {
			logger.error(ParamUtil.getErrMsgStrOfOriginalException(e));
			e.printStackTrace();
		} catch (IOException readline) {
			logger.error(ParamUtil.getErrMsgStrOfOriginalException(readline));
			readline.printStackTrace();
		}
	}
	public void initParams() throws IOException{
		pythonName = Configuration.getConfiguration().getPythonName();
		pythonPath = Configuration.getConfiguration().getPythonPath();
		caseListPath = Configuration.getConfiguration().getCaseListPath();
		caseListName = Configuration.getConfiguration().getCaseListName();
		caseStatusPath = Configuration.getConfiguration().getCaseStatusPath();
		casespartdbPath = Configuration.getConfiguration().getSpaAndrtdb();
		caseHandleNum = Configuration.getConfiguration().getCaseHandleNum();
		messagePre = ParamUtil.getJsonObject(Constants.CASESTATUSPRE, "", "", Fiforeader.readLastLine(casespartdbPath));
	}
	
	private synchronized void sendCaseStatus(String reqType, String body){
		JSONObject tmp = JSONObject.fromObject(messagePre);
		String reqMessage = ParamUtil.getJsonObject(tmp, reqType, "", body).toString();
		int counter =0;
		isSendCaseStatus = true;
		while(isSendCaseStatus){
			try {
				//Avoid status repeats
				if(null != caseStatusInfo && DigestUtils.md5Hex(reqMessage).equals(DigestUtils.md5Hex(caseStatusInfo.toString()))){ 
					logger.info("case list last status "+caseStatusInfo.toString());
					return;
				}
				if(counter==0){
					sendMessageWithRetry(reqMessage);
				}
			} catch (CaseSocketConnectionException e) {
			}finally{
				try {
					Thread.sleep(Configuration.getConfiguration().getSocketErrorTimeout()/60);
					counter++;
					if(counter == 60){
						counter=0;
					}
				} catch (InterruptedException interruptedException) {
					logger.error(ParamUtil.getErrMsgStrOfOriginalException(interruptedException));
				}
			}
		}
		caseStatusInfo=JSONObject.fromObject(reqMessage);
	}
	private synchronized void readAndSendMessage(String filePath) throws Exception{
//		Running: 1/51  1234.json
//		Finished: success: [“1234.json”], fail:[“1235.json”]
		String result = Fiforeader.readLastLine(filePath);
		logger.info("[case status from channel:]"+result);
		if(result != null && !result.equals("") && !result.equals("[\"\"]")){
			if(result.contains(Constants.CASESTATUSRUNNINGSIGN)) {
				String [] resultStatus = result.split(":");
				JSONObject taskStatus = JSONObject.fromObject(Constants.CASESTATUSRUNNING);
				JSONObject running = taskStatus.getJSONObject(Constants.TASKSTATUS);
				running.put(Constants.STATUS, resultStatus[0].trim().replaceAll("\"", ""));
				running.put(Constants.RUNNINGCASE, resultStatus[1].trim().replaceAll("\"", ""));
				sendCaseStatus(Constants.CASEREQTYPUP, taskStatus.toString());
			}else{
				String [] tmp = result.split(";");
				JSONObject taskResult = JSONObject.fromObject(Constants.CASESTATUSFINISHED);
				JSONObject finshed = taskResult.getJSONObject(Constants.TASKRESULT);
				JSONArray successFromTxt = JSONArray.fromObject(tmp[0].split(":")[2].trim());
				JSONArray success = finshed.getJSONArray(Constants.SUCCESS);
				for(int i=0;i<successFromTxt.size(); i++){
					JSONObject temp = new JSONObject();
					temp.put(Constants.NAME, successFromTxt.getString(i));
					temp.put(Constants.TIME, "");
					success.add(temp);
				}
//				finshed.put(Constants.SUCCESS, JSONArray.fromObject(tmp[0].split(":")[2].trim()));
				finshed.put(Constants.FAIL, JSONArray.fromObject(tmp[1].split(":")[1].trim()));
//				"runningcase",tmp[0].split(":")[0].trim().replaceAll("\"", "")
				sendCaseStatus(Constants.CASEINORUP, taskResult.toString());
			}
		}else{
			logger.info("[case status is null]");
		}
	}
	
	public static void main(String[] args) throws Exception {
		CaseMonitorThread c = new CaseMonitorThread();
		c.initParams();
		c.readAndSendMessage("D:\\test\\test.txt");
	}
	/**
	 * socket reconnect
	 * @param reqPacket req message
	 * @return
	 * @throws CaseSocketConnectionException 
	 * @throws Exception 
	 * @throws ChdpParamIncompletedException 
	 * @throws ChdpSocketConnectionException 
	 * @throws IOException 
	 */
	private void sendMessageWithRetry(String reqPacket) throws CaseSocketConnectionException{
		if(CaseCache.getHealthyServers().size()==0){
			logger.error("[Request failed - no available server list]");
			throw new CaseSocketConnectionException("[Request failed - no available server list]");
		}
		//Select the server to get the connection information
		SocketInfo info = pollingServers();
		logger.debug("Service provider \n "+info.getName()+";");
		int i=0;
		try {
			//Request interface via socket
			CommonsUtil.connector(info, reqPacket);
			logger.info("[send message:]"+reqPacket);
		} catch (SocketException socketException) {
			logger.error("retry:"+(i+1)+" time");
			i++;
		} catch (NumberFormatException e) {
			logger.error("[Reconnection to the maximum number of times, the connection failed]",e);
			new CaseParamIncompletedException("[Server parameter configuration error]",e);
		} catch (IOException e) {
			logger.error("[Reconnection to the maximum number of times, the connection failed]",e);
			new CaseSocketConnectionException(info.getName()+" server connection failed",e);
		}catch (InterruptedException interruptedException){
			new InterruptedException();
		}catch (Exception e){
			logger.error("[socket connection failed]",e);
		}
	}
	
	/**
	 * @return socket conn info
	 */
	private SocketInfo pollingServers(){
		List<SocketInfo> healthyServers = CaseCache.getHealthyServers();
		int autoIncrement = AutoIncrement.getAutoIncrement().get();
		logger.debug("Number of health servers：" + healthyServers.size());
		logger.debug("autosign：" + autoIncrement);
		
        int balanceIndex = Math.abs(autoIncrement % healthyServers.size());
        logger.debug("Load balance sign：" + balanceIndex);
		return healthyServers.get(balanceIndex);
	}
	
	//Cache loading
	class HotCache implements Runnable{
		int refreshCaseConfCounter = 0;
		@SuppressWarnings("static-access")
		@Override
		public void run() {
			//Polling the server cache according to the configured sleep time, read if there is a modification
			while(isStopHotCache){
				String initResult = "";
				try {
					if(refreshCaseConfCounter==10){
						refreshCaseConfCounter = 0;
						initParams();
						if("".equals(messagePre.getJSONObject(Constants.BODY).getJSONObject(Constants.LAB).get(Constants.IP))){
							logger.error("[please build spa and rtdb ]");
							continue;
						}
						initResult = CaseCache.refreshCache(InitListener.getConfigPath()+File.separator+"caseclientconf.properties");
						if(!Constants.CASE_SUCCESS.equals(initResult)){
							throw new CaseInitListenerException("[Health detection thread corrupted or not running]");
						}
					}
				} catch (CaseParamIncompletedException paramIncompletedException) {
					logger.error(paramIncompletedException.getMessage(),paramIncompletedException);
					paramIncompletedException.printStackTrace();
				} catch (CaseRefreshConfCacheException refreshConfCacheException) {
					logger.error(refreshConfCacheException.getMessage(),refreshConfCacheException);
					refreshConfCacheException.printStackTrace();
				} catch (CaseInitListenerException initListenerException) {
					logger.error(initListenerException.getMessage(),initListenerException);
					initListenerException.printStackTrace();
				} catch (IOException readline) {
					logger.error(readline.getMessage(),readline);
					readline.printStackTrace();
				}
				
				try {
					Socket socket = CommonsUtil.socket;
					if(socket!=null && socket.isConnected() && !socket.isClosed()){
						String serverSendInfo = CommonsUtil.readInfo();
						logger.info("[Received server message is : ]"+serverSendInfo);
						if(serverSendInfo.contains("command")){
							new Thread(new ExecCommand(serverSendInfo)).start();
						}else if(serverSendInfo.startsWith(Constants.AVAILABLECASE)){//caselist
							JSONObject caseListAndUUID = JSONObject.fromObject(serverSendInfo.replace(Constants.AVAILABLECASE+":", ""));
							if(!cacheCaseListUUID.equals(caseListAndUUID.get("uuid").toString())){
								new Thread(new ExecPython(caseListAndUUID)).start();
								cacheCaseListUUID=caseListAndUUID.get("uuid").toString();
							}
						}else if(serverSendInfo.equals(Constants.UPDATE)){
							isSendCaseStatus = false;
							isOrNotRecon=false;
						}else if(serverSendInfo.equals(Constants.ACKUPDATE)){
							isSendCaseStatus = false;
							isOrNotRecon=false;
						}else if(serverSendInfo.equals(Constants.COMMANDSUCCESS)){
							isSendCommandStatus = false;
						}else if(serverSendInfo.equals(Constants.CASE_EMBEDDED_MESSAGERESP)){
							CaseCache.appendHealthyServers(socketInfo);
						}else{
							logger.info("[wait receive message of server...]");
						}
					}else{
						logger.info("[client socket not use ]");
					}
					refreshCaseConfCounter++;
				} catch (Exception e) {
					logger.error("[read or write case status exception]"+ParamUtil.getErrMsgStrOfOriginalException(e));
				}finally{
					long refreshTime = Configuration.getConfiguration().getRefreshTimeBreak();
					try {
						Thread.currentThread().sleep(refreshTime);
					} catch (InterruptedException e) {}
				}
			}
		}
	}
	
	
	//exec python
	class ExecPython implements Runnable{
		JSONObject receiveInfo;
		public ExecPython(JSONObject receiveInfo){
			this.receiveInfo=receiveInfo;
		}
		@Override
		public void run() {
			try {
				sendCaseStatus(Constants.CASELISTACK, Constants.CASESTATUSREADY);
				Fifowriter.writerFile(caseListPath, caseListName, receiveInfo.get("case_list").toString(), Integer.parseInt(caseHandleNum));
				Fifowriter.writerFile("", caseStatusPath,"", 0);
				//exec python
				isOrNotStartedPy = PythonUtil.exec("nohup "+pythonPath+File.separator+pythonName+" "+pythonPath+" "+caseListPath+File.separator+caseListName+" > "+pythonPath+File.separator+"python.out 2>&1 &");
				do{
					Thread.sleep(Configuration.getConfiguration().getSocketErrorTimeout());
					readAndSendMessage(caseStatusPath);
				}while(isOrNotStartedPy && !(caseStatusInfo.getJSONObject(Constants.BODY).getJSONObject(Constants.TASKRESULT).getJSONArray(Constants.SUCCESS).size()>0) && !(caseStatusInfo.getJSONObject(Constants.BODY).getJSONObject(Constants.TASKRESULT).getJSONArray(Constants.FAIL).size()>0));
				sendCaseStatus(Constants.CASEREQTYPUP, Constants.CASESTATUSIDLE);
				isOrNotStartedPy = false;
			} catch (Exception e) {
				logger.error("[exec python is exception]"+ParamUtil.getErrMsgStrOfOriginalException(e));
				sendCaseStatus(Constants.CASEREQTYPUP, Constants.CASESTATUSFAIL);
			}
		}
	}
	
	//exec command
	class ExecCommand implements Runnable{
		String receiveInfo;
		public ExecCommand(String receiveInfo){
			this.receiveInfo=receiveInfo;
		}
		@Override
		public void run() {
			JSONArray commandArr = new JSONArray().fromObject(receiveInfo);
//			sendCaseStatus(Constants.COMMANDSUCCESS);
		}
		public void sendCommandResult(String reqMessage) throws CaseSocketConnectionException{
			while(isSendCommandStatus){
				//TODO
				sendMessageWithRetry(reqMessage);
			}
			isSendCommandStatus = true;
		}
	}
	
	//Python Monitor
	class PythonMonitor implements Runnable{
		@Override
		public void run() {
			Long scanTime = Configuration.getConfiguration().getMonitorPyBreak();
			while(isStopPythonMonitor){
				try {
					if(isOrNotStartedPy){
						if(!PythonUtil.getProcess(pythonName)){
							if(!Fiforeader.readLastLine(caseStatusPath).contains(Constants.CASESTATUSFINSHED)){
								//send case failed
								sendCaseStatus(Constants.CASEREQTYPUP, Constants.CASESTATUSFAIL);
								isOrNotStartedPy = false;
							}
						}
					}else{
						//first exec
						if(null == caseStatusInfo && !PythonUtil.getProcess(pythonName)){
							sendCaseStatus(Constants.CASEREQTYPUP, Constants.CASESTATUSIDLE);
						}
					}
				} catch (IOException e) {
					logger.error("[read case status exception ]"+ParamUtil.getErrMsgStrOfOriginalException(e));
					e.printStackTrace();
				} 
				try {
					Thread.sleep(scanTime);
					logger.info("[python monitor is start...]");
				} catch (InterruptedException e) {
					logger.error(Thread.currentThread().getName()+"monitor python thread sleep exception",e);
					e.printStackTrace();
				}
			}
		}
	}
	
	//Health detection
	class HealthChecker implements Runnable{
		@SuppressWarnings("static-access")
		@Override
		public void run() {
			Long scanTime = Configuration.getConfiguration().getHealthycheckTimeBreak();
			while(isStopHeathyCheck){
				//Health detection
				logger.debug("Health detection start time:"+new Date());
				int retryTimes = Configuration.getConfiguration().getHealthycheckErrorTime();
				//No server list available
				if(CaseCache.getHealthyServers().isEmpty()){
					//TODO email
					logger.error("[No server list available]");
				}
				//Check server health
				try {
					checkHealthy(retryTimes);
				} catch (CaseParamIncompletedException CaseParamIncompletedException) {
					logger.error(CaseParamIncompletedException.getMessage(),CaseParamIncompletedException);
					CaseParamIncompletedException.printStackTrace();
				}
				logger.debug("server list length:"+Configuration.getConfiguration().getServerList().size());
				logger.debug("Health detection end time:"+new Date());
				try {
					Thread.currentThread().sleep(scanTime);
				} catch (InterruptedException e) {
					logger.error(Thread.currentThread().getName()+"health thread sleep exception",e);
					e.printStackTrace();
				}
			}
		}
		
		/**
		 * Send embedded messages to check server health
		 * @param retryTimes
		 * @throws CaseParamIncompletedException 
		 * @throws SocketException 
		 */
		private void checkHealthy(Integer retryTimes) throws CaseParamIncompletedException{
			for(int j=0; j<Configuration.getConfiguration().getServerList().size(); j++){
				socketInfo = Configuration.getConfiguration().getServerList().get(j);
				for (int i = 0; i < retryTimes; i++) {
					try {
						CommonsUtil.connector(socketInfo, Constants.CASE_EMBEDDED_MESSAGEREQ);
						if(!PythonUtil.getProcess(pythonName)&&isOrNotRecon){
							sendCaseStatus(Constants.CASEREQTYPUP, Constants.CASESTATUSIDLE);
							isOrNotRecon=false;
						}
						break;
					} catch (Exception e) {
						try {
							Thread.sleep(Configuration.getConfiguration().getSocketErrorTimeout());
						} catch (InterruptedException interruptedException) {
							logger.error(ParamUtil.getErrMsgStrOfOriginalException(interruptedException));
						}
						//Try to get the maximum number of times to remove the server from the list of available servers
						logger.error("retry:"+i, e);
						if(i==retryTimes-1){
							logger.error("The maximum number of times of reconnection "+socketInfo.getName()+" Server connection failed ",e);
							CaseCache.deleteHealthyServers(socketInfo);
							CommonsUtil.close();
							isOrNotRecon=true;
						}else{
							continue; 
						}
					}
				}
			}
		}
	}
}
