package pro.belbix.ethparser.web3.uniswap.parser;

import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.PreDestroy;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import pro.belbix.ethparser.dto.DtoI;
import pro.belbix.ethparser.dto.UniswapDTO;
import pro.belbix.ethparser.model.UniswapTx;
import pro.belbix.ethparser.web3.EthBlockService;
import pro.belbix.ethparser.web3.ParserInfo;
import pro.belbix.ethparser.web3.Web3Parser;
import pro.belbix.ethparser.web3.Web3Service;
import pro.belbix.ethparser.web3.harvest.parser.UniToHarvestConverter;
import pro.belbix.ethparser.web3.prices.PriceProvider;
import pro.belbix.ethparser.web3.uniswap.UniOwnerBalanceCalculator;
import pro.belbix.ethparser.web3.uniswap.db.UniswapDbService;
import pro.belbix.ethparser.web3.uniswap.decoder.UniswapLpLogDecoder;

@Service
@Log4j2
public class UniswapLpLogParser implements Web3Parser {

    private static final AtomicBoolean run = new AtomicBoolean(true);
    private final UniswapLpLogDecoder uniswapLpLogDecoder = new UniswapLpLogDecoder();
    private final Web3Service web3Service;
    private final BlockingQueue<Log> logs = new ArrayBlockingQueue<>(100);
    private final BlockingQueue<DtoI> output = new ArrayBlockingQueue<>(100);
    private final UniswapDbService uniswapDbService;
    private final EthBlockService ethBlockService;
    private final PriceProvider priceProvider;
    private final UniToHarvestConverter uniToHarvestConverter;
    private final ParserInfo parserInfo;
    private final UniOwnerBalanceCalculator uniOwnerBalanceCalculator;
    private Instant lastTx = Instant.now();
    private long count = 0;

    public UniswapLpLogParser(Web3Service web3Service,
                              UniswapDbService uniswapDbService,
                              EthBlockService ethBlockService,
                              PriceProvider priceProvider,
                              UniToHarvestConverter uniToHarvestConverter,
                              ParserInfo parserInfo,
                              UniOwnerBalanceCalculator uniOwnerBalanceCalculator) {
        this.web3Service = web3Service;
        this.uniswapDbService = uniswapDbService;
        this.ethBlockService = ethBlockService;
        this.priceProvider = priceProvider;
        this.uniToHarvestConverter = uniToHarvestConverter;
        this.parserInfo = parserInfo;
        this.uniOwnerBalanceCalculator = uniOwnerBalanceCalculator;
    }

    @Override
    public void startParse() {
        log.info("Start parse Uniswap logs");
        parserInfo.addParser(this);
        web3Service.subscribeOnLogs(logs);
        new Thread(() -> {
            while (run.get()) {
                Log ethLog = null;
                try {
                    ethLog = logs.poll(1, TimeUnit.SECONDS);
                    count++;
                    if (count % 100 == 0) {
                        log.info(this.getClass().getSimpleName() + " handled " + count);
                    }
                    UniswapDTO dto = parseUniswapLog(ethLog);
                    if (dto != null) {
                        lastTx = Instant.now();
                        enrichDto(dto);
                        uniOwnerBalanceCalculator.fillBalance(dto);
                        uniToHarvestConverter.addDtoToQueue(dto);
                        boolean success = uniswapDbService.saveUniswapDto(dto);
                        if (success) {
                            output.put(dto);
                        }
                    }
                } catch (Exception e) {
                    UniswapLpLogParser.log.error("Error uniswap parser loop " + ethLog, e);
                }
            }
        }).start();
    }

    public UniswapDTO parseUniswapLog(Log ethLog) {
        UniswapTx tx = new UniswapTx();
        uniswapLpLogDecoder.decode(tx, ethLog);
        if (tx.getHash() == null) {
            return null;
        }

        UniswapDTO dto = tx.toDto();

        //enrich owner
        TransactionReceipt receipt = web3Service.fetchTransactionReceipt(dto.getHash());
        dto.setOwner(receipt.getFrom());

        //enrich date
        dto.setBlockDate(
            ethBlockService.getTimestampSecForBlock(ethLog.getBlockHash(), ethLog.getBlockNumber().longValue()));

        if (dto.getLastPrice() == null) {
            Double otherCoinPrice = priceProvider.getPriceForCoin(dto.getOtherCoin(), dto.getBlock().longValue());
            if (otherCoinPrice != null) {
                dto.setPrice((dto.getOtherAmount() * otherCoinPrice) / dto.getAmount());
            } else {
                throw new IllegalStateException("Price not found " + dto.print());
            }
        }

        log.info(dto.print());

        return dto;
    }

    private void enrichDto(UniswapDTO dto) {
        dto.setLastGas(web3Service.fetchAverageGasPrice());
    }

    @Override
    public BlockingQueue<DtoI> getOutput() {
        return output;
    }

    @PreDestroy
    public void stop() {
        run.set(false);
    }

    @Override
    public Instant getLastTx() {
        return lastTx;
    }
}
