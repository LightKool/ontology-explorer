/*
 * Copyright (C) 2018 The ontology Authors
 * This file is part of The ontology library.
 *
 * The ontology is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The ontology is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with The ontology.  If not, see <http://www.gnu.org/licenses/>.
 */


package com.github.ontio.thread;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.github.ontio.common.Address;
import com.github.ontio.common.Helper;
import com.github.ontio.config.ParamsConfig;
import com.github.ontio.core.transaction.Transaction;
import com.github.ontio.mapper.ContractMapper;
import com.github.ontio.mapper.Oep5Mapper;
import com.github.ontio.mapper.Oep8Mapper;
import com.github.ontio.model.common.EventTypeEnum;
import com.github.ontio.model.common.TransactionTypeEnum;
import com.github.ontio.model.dao.Oep5;
import com.github.ontio.model.dao.Oep5Dragon;
import com.github.ontio.model.dao.Oep8;
import com.github.ontio.model.dao.TxDetail;
import com.github.ontio.network.exception.RestfulException;
import com.github.ontio.service.CommonService;
import com.github.ontio.smartcontract.neovm.abi.BuildParams;
import com.github.ontio.utils.ConstantParam;
import com.github.ontio.utils.ReSyncConstantParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.AsyncResult;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;


/**
 * @author zhouq
 * @version 1.0
 * @date 2018/3/13
 */
@Slf4j
@Component
public class TxReSyncThread {

	private static ThreadLocal<Map<String, Boolean>> IS_OEPTX_FLAG = new ThreadLocal<>();

	private final ParamsConfig paramsConfig;

	private final CommonService commonService;

	private final ContractMapper contractMapper;

	private final Oep8Mapper oep8Mapper;

	private final Oep5Mapper oep5Mapper;

	@Autowired
	public TxReSyncThread(ParamsConfig paramsConfig, ContractMapper contractMapper, CommonService commonService,
			Oep8Mapper oep8Mapper, Oep5Mapper oep5Mapper) {
		this.paramsConfig = paramsConfig;
		this.contractMapper = contractMapper;
		this.commonService = commonService;
		this.oep8Mapper = oep8Mapper;
		this.oep5Mapper = oep5Mapper;
	}

	@Async
	public Future<String> asyncHandleTx(JSONObject txJson, int blockHeight, int blockTime, int indexInBlock, String contractHash) throws Exception {
		Map<String, Boolean> map = new HashMap<>();
		map.put(ConstantParam.IS_OEP4TX, false);
		map.put(ConstantParam.IS_OEP5TX, false);
		map.put(ConstantParam.IS_OEP8TX, false);
		IS_OEPTX_FLAG.set(map);

		try {
			String threadName = Thread.currentThread().getName();
			log.info("{} run--------blockHeight:{},txHash:{}", threadName, blockHeight, txJson.getString("Hash"));
			handleOneTx(txJson, blockHeight, blockTime, indexInBlock, contractHash);
			log.info("{} end-------blockHeight:{},txHash:{}", threadName, blockHeight, txJson.getString("Hash"));
			return new AsyncResult<String>("success");
		} catch (Exception e) {
			log.error("asyncHandleTx error...", e);
			throw e;
		}

	}


	/**
	 * 处理单笔交易
	 *
	 * @param txJson
	 * @param blockHeight
	 * @param blockTime
	 * @param indexInBlock
	 * @throws Exception
	 */
	public void handleOneTx(JSONObject txJson, int blockHeight, int blockTime, int indexInBlock, String contractHash) throws Exception {

		Boolean isOntidTx = false;
		int txType = txJson.getInteger("TxType");
		String txHash = txJson.getString("Hash");
		String payer = txJson.getString("Payer");
		String code = txJson.getJSONObject("Payload").getString("Code");
		String calledContractHash = parseCalledContractHash(code, txType);
		if (!contractHash.equals(calledContractHash)) {
			return;
		}
		log.info("####txType:{}, txHash:{}, calledContractHash:{}", txType, txHash, calledContractHash);

		try {
			JSONObject eventLogObj = txJson.getJSONObject("EventLog");
			log.info("eventLog:{}", eventLogObj.toJSONString());
			//eventstate 1:success 0:failed
			int confirmFlag = eventLogObj.getInteger("State");
			BigDecimal gasConsumed = new BigDecimal(eventLogObj.getLongValue("GasConsumed")).divide(ConstantParam.ONG_DECIMAL);

			//invoke smart contract transaction
			JSONArray notifyArray = eventLogObj.getJSONArray("Notify");
			//no event transaction or deploy smart contract transaction
			if (notifyArray.size() != 0) {
				JSONArray stateArray = null;
				for (int i = 0, len = notifyArray.size(); i < len; i++) {
					JSONObject notifyObj = (JSONObject) notifyArray.get(i);
					String contractAddress = notifyObj.getString("ContractAddress");

					Object object = notifyObj.get("States");
					if (object instanceof JSONArray) {
						stateArray = (JSONArray) object;
					} else {
						continue;
					}
					if (paramsConfig.ONG_CONTRACTHASH.equals(contractAddress)) {
						//gas transaction
						handleNativeTransferTx(stateArray, txType, txHash, blockHeight, blockTime, indexInBlock,
								contractAddress, gasConsumed, i + 1, notifyArray.size(), confirmFlag, payer, calledContractHash);
					} else if (ConstantParam.OEP8CONTRACTS.contains(contractAddress)) {
						//OEP8交易
						handleOep8TransferTx(stateArray, txType, txHash, blockHeight, blockTime, indexInBlock,
								gasConsumed, i + 1, confirmFlag, contractAddress, notifyArray.size(), payer, calledContractHash);

					} else if (ConstantParam.OEP5CONTRACTS.contains(contractAddress)) {
						//OEP5交易
						handleOep5TransferTxn(stateArray, txType, txHash, blockHeight, blockTime, indexInBlock,
								gasConsumed, i + 1, confirmFlag, contractAddress, ConstantParam.OEP5MAP.get(contractAddress),
								payer, calledContractHash);

					} else if (ConstantParam.OEP4CONTRACTS.contains(contractAddress)) {
						//OEP4交易
						handleOep4TransferTxn(stateArray, txType, txHash, blockHeight, blockTime, indexInBlock,
								gasConsumed, i + 1, confirmFlag, (JSONObject) ConstantParam.OEP4MAP.get(contractAddress),
								contractAddress, payer, calledContractHash);

					}
				}
			}

		} catch (RestfulException e) {
			log.error("handleOneTx RestfulException...{}", e);
			e.printStackTrace();
			throw e;
		} catch (Exception e) {
			log.error("handleOneTx error...", e);
			e.printStackTrace();
			throw e;
		}
	}

	private void handleNativeTransferTx(JSONArray stateList, int txType, String txHash, int blockHeight, int blockTime,
			int indexInBlock, String contractAddress, BigDecimal gasConsumed, int indexInTx, int notifyListSize, int confirmFlag,
			String payer, String calledContractHash) throws Exception {
		if (stateList.size() < 3) {
			return;
		}

		int eventType = EventTypeEnum.Transfer.type();
		String assetName = "";
		if (paramsConfig.ONG_CONTRACTHASH.equals(contractAddress)) {
			assetName = ConstantParam.ASSET_NAME_ONG;
		}

		String action = (String) stateList.get(0);
		//手续费不为0的情况下，notifylist的最后一个一定是收取手续费event
		if (gasConsumed.compareTo(ConstantParam.ZERO) != 0 && (indexInTx == notifyListSize) && paramsConfig.ONG_CONTRACTHASH.equals(contractAddress)) {
			action = EventTypeEnum.Gasconsume.des();
			eventType = EventTypeEnum.Gasconsume.type();
		}

		String fromAddress = (String) stateList.get(1);
		String toAddress = (String) stateList.get(2);
		BigDecimal amount = new BigDecimal((stateList.get(3)).toString());
		log.info("####fromAddress:{},toAddress:{},amount:{}####", fromAddress, toAddress, amount.toPlainString());

		TxDetail txDetail = generateTransaction(fromAddress, toAddress, assetName, amount, txType, txHash, blockHeight,
				blockTime, indexInBlock, confirmFlag, action, gasConsumed, indexInTx, eventType, contractAddress, payer,
				calledContractHash);

		// OEP交易的手续费入对应的子表
		if (paramsConfig.ONG_CONTRACTHASH.equals(contractAddress)) {
			if (IS_OEPTX_FLAG.get().get(ConstantParam.IS_OEP8TX)) {
				ReSyncConstantParam.BATCHBLOCKDTO.getOep8TxDetails().add(TxDetail.toOep8TxDetail(txDetail));
			} else if (IS_OEPTX_FLAG.get().get(ConstantParam.IS_OEP5TX)) {
				ReSyncConstantParam.BATCHBLOCKDTO.getOep5TxDetails().add(TxDetail.toOep5TxDetail(txDetail));
			} else if (IS_OEPTX_FLAG.get().get(ConstantParam.IS_OEP4TX)) {
				ReSyncConstantParam.BATCHBLOCKDTO.getOep4TxDetails().add(TxDetail.toOep4TxDetail(txDetail));
			}
		}
	}

	/**
	 * 解析这笔交易真正调用的合约hash
	 *
	 * @param code
	 * @param txType
	 * @return
	 */
	private String parseCalledContractHash(String code, Integer txType) {

		String calledContractHash = "";
		if (TransactionTypeEnum.NEOVM_INVOKECODE.type() == txType) {

			while (code.contains(ConstantParam.TXPAYLOAD_CODE_FLAG)) {
				int index = code.indexOf(ConstantParam.TXPAYLOAD_CODE_FLAG);
				code = code.substring(index + 2);
				if (code.length() < 40) {
					//native合约都是792e4e61746976652e496e766f6b65
					calledContractHash = code;
					break;
				} else if (code.length() == 40) {
					calledContractHash = Helper.reverse(code);
					break;
				}
			}
		} else if (TransactionTypeEnum.WASMVM_INVOKECODE.type() == txType) {
			calledContractHash = Helper.reverse(code.substring(0, 40));
		}

		//判断属于什么OEP类型交易
		if (ConstantParam.OEP5CONTRACTS.contains(calledContractHash)) {
			IS_OEPTX_FLAG.get().put(ConstantParam.IS_OEP5TX, true);
		} else if (ConstantParam.OEP4CONTRACTS.contains(calledContractHash)) {
			IS_OEPTX_FLAG.get().put(ConstantParam.IS_OEP4TX, true);
		} else if (ConstantParam.OEP8CONTRACTS.contains(calledContractHash)) {
			IS_OEPTX_FLAG.get().put(ConstantParam.IS_OEP8TX, true);
		}
		return calledContractHash;
	}

	/**
	 * 处理oep8交易
	 *
	 * @param stateArray
	 * @param txType
	 * @param txHash
	 * @param blockHeight
	 * @param blockTime
	 * @param indexInBlock
	 * @param gasConsumed
	 * @param indexInTx
	 * @param confirmFlag
	 * @param contractAddress
	 * @param stateSize
	 * @throws Exception
	 */
	private void handleOep8TransferTx(JSONArray stateArray, int txType, String txHash, int blockHeight,
			int blockTime, int indexInBlock, BigDecimal gasConsumed, int indexInTx,
			int confirmFlag, String contractAddress, int stateSize, String payer, String calledContractHash) throws Exception {
		if (stateArray.size() < 5) {
			TxDetail txDetail = generateTransaction("", "", "", ConstantParam.ZERO, txType, txHash, blockHeight,
					blockTime, indexInBlock, confirmFlag, "", gasConsumed, indexInTx, EventTypeEnum.Others.type(), contractAddress
					, payer, calledContractHash);

			ReSyncConstantParam.BATCHBLOCKDTO.getTxDetails().add(txDetail);
			ReSyncConstantParam.BATCHBLOCKDTO.getTxDetailDailys().add(TxDetail.toTxDetailDaily(txDetail));
			ReSyncConstantParam.BATCHBLOCKDTO.getOep8TxDetails().add(TxDetail.toOep8TxDetail(txDetail));
			return;
		}

		String action = new String(Helper.hexToBytes((String) stateArray.get(0)));
		String fromAddress = (String) stateArray.get(1);
		String toAddress = (String) stateArray.get(2);
		String tokenId = (String) stateArray.get(3);
		JSONObject oep8Obj = (JSONObject) ConstantParam.OEP8MAP.get(contractAddress + "-" + tokenId);
		if ("00".equals(fromAddress) && stateSize == 2) {
			// mint方法即增加发行量方法, 区分标志：fromAddress为“00”，同时stateSize为2
			Oep8 oep8 = Oep8.builder()
					.contractHash(contractAddress)
					.tokenId((String) stateArray.get(3))
					.totalSupply(commonService.getOep8TotalSupply(tokenId))
					.build();
			//在子线程直接更新，不批量更新
			oep8Mapper.updateByPrimaryKeySelective(oep8);
		}

		if (40 == fromAddress.length()) {
			fromAddress = Address.parse(fromAddress).toBase58();
		}
		if (40 == toAddress.length()) {
			toAddress = Address.parse(toAddress).toBase58();
		}

		BigDecimal eventAmount =
				new BigDecimal(Helper.BigIntFromNeoBytes(Helper.hexToBytes((String) stateArray.get(4))).longValue());
		log.info("OEP8TransferTx:fromaddress:{}, toaddress:{}, tokenid:{}, amount:{}", fromAddress, toAddress, tokenId,
				eventAmount);

		TxDetail txDetail = generateTransaction(fromAddress, toAddress, oep8Obj.getString("name"), eventAmount, txType, txHash,
				blockHeight,
				blockTime, indexInBlock, confirmFlag, action, gasConsumed, indexInTx, EventTypeEnum.Transfer.type(),
				contractAddress, payer, calledContractHash);

		ReSyncConstantParam.BATCHBLOCKDTO.getTxDetails().add(txDetail);
		ReSyncConstantParam.BATCHBLOCKDTO.getTxDetailDailys().add(TxDetail.toTxDetailDaily(txDetail));
		ReSyncConstantParam.BATCHBLOCKDTO.getOep8TxDetails().add(TxDetail.toOep8TxDetail(txDetail));
	}

	/**
	 * 处理oep5交易
	 *
	 * @param stateArray
	 * @param txType
	 * @param txHash
	 * @param blockHeight
	 * @param blockTime
	 * @param indexInBlock
	 * @param gasConsumed
	 * @param indexInTx
	 * @param confirmFlag
	 * @param contractAddress
	 * @param oep5Obj
	 * @throws Exception
	 */
	private void handleOep5TransferTxn(JSONArray stateArray, int txType, String txHash, int blockHeight,
			int blockTime, int indexInBlock, BigDecimal gasConsumed, int indexInTx, int confirmFlag,
			String contractAddress, JSONObject oep5Obj, String payer, String calledContractHash) throws Exception {

		Boolean isTransfer = Boolean.FALSE;
		String action = new String(Helper.hexToBytes((String) stateArray.get(0)));
		//只解析birth和transfer合约方法
		if (!(action.equalsIgnoreCase("transfer") || action.equalsIgnoreCase("birth"))) {
			TxDetail txDetail = generateTransaction("", "", "", ConstantParam.ZERO, txType, txHash, blockHeight,
					blockTime, indexInBlock, confirmFlag, action, gasConsumed, indexInTx, EventTypeEnum.Others.type(),
					contractAddress, payer, calledContractHash);

			ReSyncConstantParam.BATCHBLOCKDTO.getTxDetails().add(txDetail);
			ReSyncConstantParam.BATCHBLOCKDTO.getTxDetailDailys().add(TxDetail.toTxDetailDaily(txDetail));
			ReSyncConstantParam.BATCHBLOCKDTO.getOep5TxDetails().add(TxDetail.toOep5TxDetail(txDetail));
			return;
		}

		String fromAddress = (String) stateArray.get(1);
		if (40 == fromAddress.length()) {
			fromAddress = Address.parse(fromAddress).toBase58();
		}
		String toAddress = (String) stateArray.get(2);
		if (40 == toAddress.length()) {
			toAddress = Address.parse(toAddress).toBase58();
		}

		String assetName = "";
		BigDecimal amount = ConstantParam.ZERO;
		//云斗龙特殊处理,记录birth出来的云斗龙信息
		if ("HyperDragons".equalsIgnoreCase(oep5Obj.getString("name"))) {
			// 如果是birth方法，tokenid位置在2；如果是transfer方法，tokenid位置在3
			String dragonId = "";
			if ("birth".equalsIgnoreCase(action)) {
				dragonId = Helper.BigIntFromNeoBytes(Helper.hexToBytes((String) stateArray.get(2))).toString();
				fromAddress = "";
				toAddress = "";

				Oep5Dragon oep5Dragon = Oep5Dragon.builder()
						.contractHash(contractAddress)
						.assetName(ConstantParam.ASSET_NAME_DRAGON + dragonId)
						.jsonUrl(getDragonUrl(contractAddress, dragonId))
						.build();
				ReSyncConstantParam.BATCHBLOCKDTO.getOep5Dragons().add(oep5Dragon);
			} else {
				amount = ConstantParam.ONE;
				dragonId = Helper.BigIntFromNeoBytes(Helper.hexToBytes((String) stateArray.get(3))).toString();
				isTransfer = Boolean.TRUE;
			}
			assetName = ConstantParam.ASSET_NAME_DRAGON + dragonId;
		} else {
			//OEP5初始化交易，更新total_supply。且tokenid位置在2
			if ("birth".equalsIgnoreCase(action)) {
				assetName = oep5Obj.getString("symbol") + stateArray.get(2);
				fromAddress = "";
				toAddress = "";

				Long totalSupply = commonService.getOep5TotalSupply(contractAddress);
				Oep5 oep5 = Oep5.builder()
						.contractHash(contractAddress)
						.totalSupply(totalSupply)
						.build();
				//在子线程直接更新，不批量更新
				oep5Mapper.updateByPrimaryKeySelective(oep5);

			} else if ("transfer".equalsIgnoreCase(action)) {
				//transfer方法，tokenid在位置3
				assetName = oep5Obj.getString("symbol") + stateArray.get(3);
				amount = ConstantParam.ONE;
				isTransfer = Boolean.TRUE;
			}
		}

		log.info("OEP5TransferTx:fromaddress:{}, toaddress:{}, assetName:{}", fromAddress, toAddress, assetName);

		TxDetail txDetail = generateTransaction(fromAddress, toAddress, assetName, amount, txType, txHash, blockHeight,
				blockTime, indexInBlock, confirmFlag, action, gasConsumed, indexInTx, EventTypeEnum.Transfer.type(),
				contractAddress, payer, calledContractHash);

		ReSyncConstantParam.BATCHBLOCKDTO.getTxDetails().add(txDetail);
		ReSyncConstantParam.BATCHBLOCKDTO.getTxDetailDailys().add(TxDetail.toTxDetailDaily(txDetail));
		ReSyncConstantParam.BATCHBLOCKDTO.getOep5TxDetails().add(TxDetail.toOep5TxDetail(txDetail));
	}

	private void handleOep4TransferTxn(JSONArray stateArray, int txType, String txHash, int blockHeight,
			int blockTime, int indexInBlock, BigDecimal gasConsumed, int indexInTx, int confirmFlag,
			JSONObject oep4Obj, String contractHash, String payer, String calledContractHash) throws Exception {
		String fromAddress = "";
		String toAddress = "";
		BigDecimal eventAmount = new BigDecimal("0");
		Boolean isTransfer = Boolean.FALSE;

		if (stateArray.size() != 4) {
			log.warn("Invalid OEP-4 event in transaction {}", txHash);
			TxDetail txDetail = generateTransaction(fromAddress, toAddress, "", eventAmount, txType, txHash, blockHeight,
					blockTime, indexInBlock, confirmFlag, "", gasConsumed, indexInTx, EventTypeEnum.Others.type(), contractHash,
					payer, calledContractHash);
			ReSyncConstantParam.BATCHBLOCKDTO.getTxDetails().add(txDetail);
			ReSyncConstantParam.BATCHBLOCKDTO.getTxDetailDailys().add(TxDetail.toTxDetailDaily(txDetail));
			ReSyncConstantParam.BATCHBLOCKDTO.getOep4TxDetails().add(TxDetail.toOep4TxDetail(txDetail));
			return;
		}

		String action = new String(Helper.hexToBytes((String) stateArray.get(0)));

		if (action.equalsIgnoreCase("transfer")) {
			try {
				fromAddress = Address.parse((String) stateArray.get(1)).toBase58();
			} catch (Exception e) {
				fromAddress = (String) stateArray.get(1);
			}

			try {
				toAddress = Address.parse((String) stateArray.get(2)).toBase58();
				eventAmount = BigDecimalFromNeoVmData((String) stateArray.get(3));
			} catch (Exception e) {
				log.warn("Parsing OEP-4 transfer event failed in transaction {}", txHash);
			}
			log.info("Parsing OEP4 transfer event: from {}, to {}, amount {}", fromAddress, toAddress, eventAmount);
			isTransfer = Boolean.TRUE;
		}

		if (paramsConfig.PAX_CONTRACTHASH.equals(contractHash)) {
			if (action.equalsIgnoreCase("IncreasePAX")) {
				try {
					fromAddress = paramsConfig.PAX_CONTRACTHASH;
					toAddress = Address.parse((String) stateArray.get(1)).toBase58();
					eventAmount = BigDecimalFromNeoVmData((String) stateArray.get(2));
				} catch (Exception e) {
					log.info("Parsing increase PAX event failed in transaction {}", txHash);
				}
				log.info("Parsing increase PAX event: from {} to {} amount {}", fromAddress, toAddress, eventAmount);
			}

			if (action.equalsIgnoreCase("DecreasePAX")) {
				try {
					fromAddress = Address.parse((String) stateArray.get(1)).toBase58();
					toAddress = paramsConfig.PAX_CONTRACTHASH;
					eventAmount = BigDecimalFromNeoVmData((String) stateArray.get(3));
				} catch (Exception e) {
					log.info("Parsing increase PAX event failed in transaction {}", txHash);
				}
				log.info("Parsing decrease PAX event: from {} to {} amount {}", fromAddress, toAddress, eventAmount);
			}
		}

		String assetName = oep4Obj.getString("symbol");
		Integer decimals = oep4Obj.getInteger("decimals");
		BigDecimal amount = eventAmount.divide(new BigDecimal(Math.pow(10, decimals)), decimals, RoundingMode.HALF_DOWN);
		TxDetail txDetail = generateTransaction(fromAddress, toAddress, assetName, amount, txType, txHash, blockHeight,
				blockTime, indexInBlock, confirmFlag, EventTypeEnum.Transfer.des(), gasConsumed, indexInTx,
				EventTypeEnum.Transfer.type(), contractHash, payer, calledContractHash);

		ReSyncConstantParam.BATCHBLOCKDTO.getTxDetails().add(txDetail);
		ReSyncConstantParam.BATCHBLOCKDTO.getTxDetailDailys().add(TxDetail.toTxDetailDaily(txDetail));
		ReSyncConstantParam.BATCHBLOCKDTO.getOep4TxDetails().add(TxDetail.toOep4TxDetail(txDetail));
	}

	private BigDecimal BigDecimalFromNeoVmData(String value) {
		return new BigDecimal(Helper.BigIntFromNeoBytes(Helper.hexToBytes(value)).longValue());
	}

	/**
	 * 构造基本交易信息dao
	 *
	 * @param fromAddress
	 * @param toAddress
	 * @param assetName
	 * @param account
	 * @param txType
	 * @param txHash
	 * @param blockHeight
	 * @param blockTime
	 * @param indexInBlock
	 * @param confirmFlag
	 * @param action
	 * @param gasConsumed
	 * @param indexInTx
	 * @param eventType
	 * @param contractAddress
	 * @param payer
	 * @return
	 */
	private TxDetail generateTransaction(String fromAddress, String toAddress, String assetName, BigDecimal account, int txType,
			String txHash, int blockHeight, int blockTime, int indexInBlock, int confirmFlag,
			String action, BigDecimal gasConsumed, int indexInTx, int eventType, String contractAddress,
			String payer, String calledContractHash) {
		return TxDetail.builder()
				.txHash(txHash)
				.txType(txType)
				.txTime(blockTime)
				.blockHeight(blockHeight)
				.blockIndex(indexInBlock)
				.confirmFlag(confirmFlag)
				.description(action)
				.fee(gasConsumed)
				.txIndex(indexInTx)
				.eventType(eventType)
				.payer(payer)
				.calledContractHash(calledContractHash)
				.contractHash(contractAddress)
				.amount(account)
				.assetName(assetName)
				.fromAddress(fromAddress)
				.toAddress(toAddress)
				.build();
	}

	/**
	 * 处理交易基本信息
	 *
	 * @param txType
	 * @param txHash
	 * @param blockHeight
	 * @param blockTime
	 * @param indexInBlock
	 * @param confirmFlag
	 * @param action
	 * @param gasConsumed
	 * @param indexInTx
	 * @param eventType
	 * @param contractAddress
	 * @param payer
	 * @throws Exception
	 */
	private void insertTxBasicInfo(int txType, String txHash, int blockHeight, int blockTime,
			int indexInBlock, int confirmFlag, String action, BigDecimal gasConsumed, int indexInTx,
			int eventType, String contractAddress, String payer, String calledContractHash) throws Exception {
//
//        TxDetail txDetail = generateTransaction("", "", "", ConstantParam.ZERO, txType, txHash, blockHeight,
//                blockTime, indexInBlock, confirmFlag, action, gasConsumed, indexInTx, eventType, contractAddress, payer, 
//                calledContractHash);
//
//        ReSyncConstantParam.BATCHBLOCKDTO.getTxDetails().add(txDetail);
//        ReSyncConstantParam.BATCHBLOCKDTO.getTxDetailDailys().add(TxDetail.toTxDetailDaily(txDetail));
//
//        // OEP合约交易插入对应的子表
//        if (IS_OEPTX_FLAG.get().get(ConstantParam.IS_OEP8TX)) {
//            ReSyncConstantParam.BATCHBLOCKDTO.getOep8TxDetails().add(TxDetail.toOep8TxDetail(txDetail));
//        } else if (IS_OEPTX_FLAG.get().get(ConstantParam.IS_OEP5TX)) {
//            ReSyncConstantParam.BATCHBLOCKDTO.getOep5TxDetails().add(TxDetail.toOep5TxDetail(txDetail));
//        } else if (IS_OEPTX_FLAG.get().get(ConstantParam.IS_OEP4TX)) {
//            ReSyncConstantParam.BATCHBLOCKDTO.getOep4TxDetails().add(TxDetail.toOep4TxDetail(txDetail));
//        }
	}


	/**
	 * 获取dragon的信息
	 *
	 * @param contractHash
	 * @param tokenId
	 * @return
	 */
	private String getDragonUrl(String contractHash, String tokenId) {
		try {
			List paramList = new ArrayList<>();
			paramList.add("tokenMetadata".getBytes());

			List args = new ArrayList();
			args.add(tokenId);
			paramList.add(args);
			byte[] params = BuildParams.createCodeParamsScript(paramList);

			Transaction tx = ConstantParam.ONT_SDKSERVICE.vm().makeInvokeCodeTransaction(Helper.reverse(contractHash), null,
					params, null, 20000, 500);
			Object obj = ConstantParam.ONT_SDKSERVICE.getConnect().sendRawTransactionPreExec(tx.toHexString());
			String jsonUrl = BuildParams.deserializeItem(Helper.hexToBytes(((JSONObject) obj).getString("Result"))).toString();

			Map<String, Object> jsonUrlMap = new HashMap<>();
			if (jsonUrl.contains(",") && jsonUrl.contains("=")) {
				jsonUrlMap.put("image", new String(Helper.hexToBytes(jsonUrl.split(",")[0].split("=")[1])));
				jsonUrlMap.put("name", new String(Helper.hexToBytes(jsonUrl.split(",")[1].split("=")[1].replaceAll("\\}", ""))));
			}

			return JSON.toJSONString(jsonUrlMap);
		} catch (Exception e) {
			log.error("getAddressOep4Balance error...", e);
			return "";
		}
	}
}