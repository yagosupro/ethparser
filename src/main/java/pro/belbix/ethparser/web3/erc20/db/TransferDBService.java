package pro.belbix.ethparser.web3.erc20.db;

import static pro.belbix.ethparser.utils.Caller.silentCall;
import static pro.belbix.ethparser.web3.ContractConstants.ZERO_ADDRESS;
import static pro.belbix.ethparser.web3.erc20.TransferType.COMMON;
import static pro.belbix.ethparser.web3.erc20.TransferType.LP_BUY;
import static pro.belbix.ethparser.web3.erc20.TransferType.LP_SELL;
import static pro.belbix.ethparser.web3.erc20.TransferType.PS_EXIT;
import static pro.belbix.ethparser.web3.erc20.TransferType.PS_STAKE;
import static pro.belbix.ethparser.web3.erc20.TransferType.REWARD;
import static pro.belbix.ethparser.web3.harvest.contracts.StakeContracts.ST_PS;
import static pro.belbix.ethparser.web3.harvest.contracts.Vaults.PS;
import static pro.belbix.ethparser.web3.harvest.contracts.Vaults.PS_V0;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import javax.persistence.EntityManager;
import javax.transaction.Transactional;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import pro.belbix.ethparser.dto.TransferDTO;
import pro.belbix.ethparser.repositories.TransferRepository;
import pro.belbix.ethparser.utils.Caller;
import pro.belbix.ethparser.web3.PriceProvider;

@Service
@Log4j2
public class TransferDBService {

    private static final Set<String> notCheckableAddresses = new HashSet<>();

    static {
        notCheckableAddresses.add(ST_PS);
        notCheckableAddresses.add(PS);
        notCheckableAddresses.add(PS_V0);
        notCheckableAddresses.add(ZERO_ADDRESS);
    }

    private final Pageable limitOne = PageRequest.of(0, 1);
    private final Pageable limitAll = PageRequest.of(0, Integer.MAX_VALUE);
    private final TransferRepository transferRepository;
    private final EntityManager entityManager;
    private final PriceProvider priceProvider;

    public TransferDBService(TransferRepository transferRepository, EntityManager entityManager,
                             PriceProvider priceProvider) {
        this.transferRepository = transferRepository;
        this.entityManager = entityManager;
        this.priceProvider = priceProvider;
    }

    @Transactional
    public boolean saveDto(TransferDTO dto) {
        if (transferRepository.existsById(dto.getId())) {
            log.warn("Duplicate transfer info " + dto);
            return false;
        }
        entityManager.persist(dto);
        checkBalances(dto);
        fillProfit(dto);
        transferRepository.save(dto);
        return true;
    }

    public boolean checkBalances(TransferDTO dto) {
        return checkBalance(dto.getOwner(), dto.getBalanceOwner(), dto.getBlockDate())
            && checkBalance(dto.getRecipient(), dto.getBalanceRecipient(), dto.getBlockDate());
    }

    private boolean checkBalance(String holder, double expectedBalance, long blockDate) {
        if (notCheckableAddresses.contains(holder.toLowerCase())) {
            return true;
        }
        Double balance = transferRepository.getBalanceForOwner(holder, blockDate);
        if (balance == null) {
            balance = 0.0;
        }
        if (Math.abs(balance - expectedBalance) > 1) {
            log.info("Wrong balance for " + holder + " dbBalance: " + balance + " != " + expectedBalance);
            return false;
        }
        return true;
    }

    // used only for recalculation
    public void fillBalances(TransferDTO dto) {
        Double balanceOwner = transferRepository.getBalanceForOwner(dto.getOwner(), dto.getBlockDate());
        if (balanceOwner == null) {
            balanceOwner = 0.0;
        }
        dto.setBalanceOwner(balanceOwner);

        Double balanceRecipient = transferRepository.getBalanceForOwner(dto.getRecipient(), dto.getBlockDate());
        if (balanceRecipient == null) {
            balanceRecipient = 0.0;
        }
        dto.setBalanceRecipient(balanceRecipient);
    }

    public void fillProfit(TransferDTO dto) {
        fillProfitForPs(dto);
        fillProfitForReward(dto);
        fillProfitForTrade(dto);
    }

    private void fillProfitForPs(TransferDTO dto) {
        if (!PS_EXIT.name().equals(dto.getType())) {
            return;
        }
        List<TransferDTO> transfers = transferRepository.fetchAllByOwnerAndRecipient(
            dto.getRecipient(),
            dto.getRecipient(),
            0,
            dto.getBlockDate() - 1);
        transfers.add(dto);
        double profit = calculatePsProfit(transfers, dto.getRecipient());
        dto.setProfit(profit);
        dto.setProfitUsd(profit * dto.getPrice());
    }

    private void fillProfitForReward(TransferDTO dto) {
        if (!REWARD.name().equals(dto.getType())) {
            return;
        }
        double farmProfit = dto.getValue();
        dto.setProfit(farmProfit);
        dto.setProfitUsd(farmProfit * dto.getPrice());
    }

    private void fillProfitForTrade(TransferDTO dto) {
        if (!LP_SELL.name().equals(dto.getType())) {
            return;
        }
        List<TransferDTO> transfers = transferRepository.fetchAllByOwnerAndRecipient(
            dto.getOwner(),
            dto.getOwner(),
            0,
            dto.getBlockDate() - 1);
        transfers.add(dto);
        calculateSellProfits(transfers, dto.getOwner());
    }

    static double calculatePsProfit(List<TransferDTO> transfers, String address) {
        double stacked = 0.0;
        double exits = 0.0;
        double lastProfit = 0.0;
        for (TransferDTO transfer : transfers) {
            lastProfit = 0;
            if (!PS_EXIT.name().equalsIgnoreCase(transfer.getType())
                && !PS_STAKE.name().equalsIgnoreCase(transfer.getType())) {
                continue;
            }

            if (PS_EXIT.name().equalsIgnoreCase(transfer.getType())) {
                exits += transfer.getValue();
            }
            //count all stacked
            if (PS_STAKE.name().equalsIgnoreCase(transfer.getType())) {
                stacked += transfer.getValue();
            }

            // return profit only for last exit, so refresh balances after each full exit
            // it is a shortcut
            // will not work in rare situation when holder has profit more than initial stake amount (impossible I guess)
            if (exits > stacked) {
                lastProfit = exits - stacked;
                stacked = 0;
                exits = 0;
            }
        }
        return lastProfit;
    }

    static void calculateSellProfits(List<TransferDTO> transfers, String owner) {
        double bought = 0;
        double boughtUsd = 0;
        for (int i = 0; i < transfers.size(); i++) {
            TransferDTO transfer = transfers.get(i);
//            //remember how many we bought
            if (LP_BUY.name().equalsIgnoreCase(transfer.getType())) {
                bought += transfer.getValue();
                boughtUsd += transfer.getValue() * transfer.getPrice();
            }

            // count transfers between accounts
            if (COMMON.name().equalsIgnoreCase(transfer.getType())) {
                if (owner.equalsIgnoreCase(transfer.getRecipient())) {
                    bought += transfer.getValue();
                    boughtUsd += transfer.getValue() * transfer.getPrice();
                } else if (owner.equalsIgnoreCase(transfer.getOwner())) {
                    bought -= transfer.getValue();
                    boughtUsd -= transfer.getValue() * transfer.getPrice();
                } else {
                    throw new IllegalStateException("Wrong owner " + owner + " for " + transfer);
                }
            }

            // let's check sells
            if (LP_SELL.name().equalsIgnoreCase(transfer.getType())) {
                if (i == 0) {
                    log.error("Wrong sequence");
                    continue;
                }
                double sell = transfer.getValue();
                double sellPrice = transfer.getPrice();
                //received tokens sells don't count
                if (bought < 0.01 && bought > -0.01) {
                    continue;
                }

                //if we sell more than bought, just skip a part for not bought tokens
                if (sell > bought) {
                    sell = bought;
                }

                if (sell <= bought) {
                    double rate = (sell / bought);
                    bought -= sell; // keep only uncovered amount
                    double coveredUsd = boughtUsd * rate;
                    boughtUsd -= coveredUsd;
                    double sellUsd = sell * sellPrice;
                    double sellProfit = sellUsd - coveredUsd;
                    transfer.setProfit(0.0);
                    transfer.setProfitUsd(sellProfit);
                }
            }
        }
    }

    private Optional<TransferDTO> fetchLastTransfer(String owner, String recipient, String type, long blockDate) {
        return silentCall(() ->
            transferRepository
                .fetchLastTransfer(owner, recipient, Collections.singletonList(type), blockDate, limitOne))
            .filter(Caller::isFilledList)
            .map(l -> l.get(0));
    }

    private Stream<TransferDTO> fetchTransfers(String owner, String recipient, List<String> types, long blockDate) {
        return silentCall(() ->
            transferRepository.fetchLastTransfer(owner, recipient, types, blockDate, limitAll))
            .filter(Caller::isFilledList)
            .stream()
            .flatMap(Collection::stream);
    }

}
