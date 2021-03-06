package pro.belbix.ethparser.web3;

import static org.web3j.abi.FunctionReturnDecoder.decodeIndexedValue;

import java.lang.reflect.ParameterizedType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.DynamicBytes;
import org.web3j.abi.datatypes.Fixed;
import org.web3j.abi.datatypes.Int;
import org.web3j.abi.datatypes.StaticArray;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Ufixed;
import org.web3j.abi.datatypes.Uint;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.crypto.Hash;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.Transaction;
import org.web3j.utils.Numeric;
import pro.belbix.ethparser.model.EthTransactionI;
import pro.belbix.ethparser.web3.contracts.ContractUtils;
import pro.belbix.ethparser.web3.deployer.decoder.DeployerActivityEnum;

@SuppressWarnings({"rawtypes", "unchecked"})
public abstract class MethodDecoder {

    protected Map<String, List<TypeReference<Type>>> parametersByMethodId = new HashMap<>();
    protected Map<String, String> methodNamesByMethodId = new HashMap<>();
    protected Map<String, String> methodIdByFullHex = new HashMap<>();

    public MethodDecoder() {
        initParameters();
    }

    public static Address[] parseAddresses(Type type) {
        List addresses = ((List) type.getValue());
        Address[] result = new Address[addresses.size()];
        int i = 0;
        for (Object a : addresses) {
            result[i] = (Address) a;
            i++;
        }
        return result;
    }

    public static BigInteger[] parseInts(Type type) {
        List values = ((List) type.getValue());
        BigInteger[] integers = new BigInteger[values.size()];
        int i = 0;
        for (Object v : values) {
            integers[i] = (BigInteger) v;
            i++;
        }
        return integers;
    }

    public static String methodSignatureToFullHex(String methodSignature) {
        final byte[] input = methodSignature.getBytes();
        final byte[] hash = Hash.sha3(input);
        return Numeric.toHexString(hash);
    }

    public static double parseAmount(BigInteger amount, String address) {
        if (amount == null) {
            return 0.0;
        }
        return new BigDecimal(amount)
            .divide(ContractUtils.getDividerByAddress(address), 99, RoundingMode.HALF_UP)
            .doubleValue();
    }

    public static List<Type> extractLogIndexedValues(Log log, List<TypeReference<Type>> parameters) {
        List<Type> indexedValues = new ArrayList<>();
        if (log == null || parameters == null || log.getData() == null) {
            return indexedValues;
        }
        final List<String> topics = log.getTopics();
        List<Type> nonIndexedValues;
        try {
            nonIndexedValues = FunctionReturnDecoder.decode(log.getData(), getNonIndexedParameters(parameters));
        } catch (NullPointerException e) {
            // it is an odd bug with loader sometimes happens when the app is not warmed up
            e.printStackTrace();
            return null;
        }
        List<TypeReference<Type>> indexedParameters = getIndexedParameters(parameters);
        for (int i = 0; i < indexedParameters.size(); i++) {
            String topic = topics.get(i + 1);
            Type value = decodeIndexedValue(topic, indexedParameters.get(i));
            indexedValues.add(value);
        }
        indexedValues.addAll(nonIndexedValues);
        return indexedValues;
    }

    public static List<TypeReference<Type>> getNonIndexedParameters(List<TypeReference<Type>> parameters) {
        return parameters.stream().filter(p -> !p.isIndexed()).collect(Collectors.toList());
    }

    public static List<TypeReference<Type>> getIndexedParameters(List<TypeReference<Type>> parameters) {
        return parameters.stream().filter(TypeReference::isIndexed).collect(Collectors.toList());
    }

    protected Optional<String> parseMethodId(Log ethLog) {
        String topic0 = ethLog.getTopics().get(0);
        return Optional.ofNullable(methodIdByFullHex.get(topic0));
    }

    protected Optional<List<TypeReference<Type>>> findParameters(String methodId) {
        return Optional.ofNullable(parametersByMethodId.get(methodId));
    }

    public EthTransactionI decodeInputData(Transaction transaction) {
        String data = transaction.getInput();
        // Corporate 2/9/21 -- Changed length from 74 to 10 to decode txns that only have method id.
        if (data.length() < 10) {
            return null;
        }
        String methodID = data.substring(0, 10);
        String input = data.substring(10);
        List<TypeReference<Type>> parameters = parametersByMethodId.get(methodID);
        if (parameters == null) {
            throw new IllegalStateException("Not found parameters for " + transaction.getHash());
        }
        List<Type> types = FunctionReturnDecoder.decode(input, parameters);
        return mapTypesToModel(types, methodID, transaction);
    }

    public abstract EthTransactionI mapTypesToModel(List<Type> types, String methodID, Transaction transaction);

    public String createMethodId(String name, List<TypeReference<Type>> parameters) {
        return methodSignatureToShortHex(createMethodSignature(name, parameters));
    }

    public String createMethodFullHex(String name, List<TypeReference<Type>> parameters) {
        return methodSignatureToFullHex(createMethodSignature(name, parameters));
    }

    public String methodSignatureToShortHex(String methodSignature) {
        return methodSignatureToFullHex(methodSignature).substring(0, 10);
    }

    String createMethodSignature(String name, List<TypeReference<Type>> parameters) {
        StringBuilder result = new StringBuilder();
        result.append(name);
        result.append("(");
        String params =
            parameters.stream().map(this::getTypeName).collect(Collectors.joining(","));
        result.append(params);
        result.append(")");
        return result.toString();
    }

    <T extends Type> String getTypeName(TypeReference<T> typeReference) {
        try {
            java.lang.reflect.Type reflectedType = typeReference.getType();

            Class<?> type;
            if (reflectedType instanceof ParameterizedType) {
                type = (Class<?>) ((ParameterizedType) reflectedType).getRawType();
                return getParameterizedTypeName(typeReference, type);
            } else {
                type = Class.forName(reflectedType.getTypeName());
                return getSimpleTypeName(type);
            }
        } catch (ClassNotFoundException e) {
            throw new UnsupportedOperationException("Invalid class reference provided", e);
        }
    }

    <T extends Type, U extends Type> String getParameterizedTypeName(
        TypeReference<T> typeReference, Class<?> type) {

        try {
            if (type.equals(DynamicArray.class)) {
                Class<U> parameterizedType = getParameterizedTypeFromArray(typeReference);
                String parameterizedTypeName = getSimpleTypeName(parameterizedType);
                return parameterizedTypeName + "[]";
            } else if (type.equals(StaticArray.class)) {
                Class<U> parameterizedType = getParameterizedTypeFromArray(typeReference);
                String parameterizedTypeName = getSimpleTypeName(parameterizedType);
                return parameterizedTypeName
                    + "["
                    + ((TypeReference.StaticArrayTypeReference) typeReference).getSize()
                    + "]";
            } else {
                throw new UnsupportedOperationException("Invalid type provided " + type.getName());
            }
        } catch (ClassNotFoundException e) {
            throw new UnsupportedOperationException("Invalid class reference provided", e);
        }
    }

    String getSimpleTypeName(Class<?> type) {
        String simpleName = type.getSimpleName().toLowerCase();

        if (type.equals(Uint.class)
            || type.equals(Int.class)
            || type.equals(Ufixed.class)
            || type.equals(Fixed.class)) {
            return simpleName + "256";
        } else if (type.equals(Utf8String.class)) {
            return "string";
        } else if (type.equals(DynamicBytes.class)) {
            return "bytes";
        } else {
            return simpleName;
        }
    }

    @SuppressWarnings("unchecked")
    <T extends Type> Class<T> getParameterizedTypeFromArray(TypeReference typeReference)
        throws ClassNotFoundException {

        java.lang.reflect.Type type = typeReference.getType();
        java.lang.reflect.Type[] typeArguments =
            ((ParameterizedType) type).getActualTypeArguments();

        String parameterizedTypeName = typeArguments[0].getTypeName();
        return (Class<T>) Class.forName(parameterizedTypeName);
    }

    public void writeParameters(Map<String, List<TypeReference<Type>>> parameters) {
        for (Map.Entry<String, List<TypeReference<Type>>> entry : parameters.entrySet()) {
            String methodName = entry.getKey();
            if (methodName.contains("#")) {
                methodName = methodName.split("#")[0];
            }

            String methodID = createMethodId(methodName, entry.getValue());
            String methodFullHex = createMethodFullHex(methodName, entry.getValue());
            parametersByMethodId.put(methodID, entry.getValue());
            methodNamesByMethodId.put(methodID, entry.getKey());
            methodIdByFullHex.put(methodFullHex, methodID);
//            System.out.println(this.getClass().getSimpleName() + " " + entry.getKey() + " " + methodID + " " + methodFullHex);
        }
    }

    private void initParameters() {
        if (parametersByMethodId.isEmpty()) {
            Map<String, List<TypeReference<Type>>> parameters = new HashMap<>();
            try {
                parameters.put("SmartContractRecorded",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address", true, false),
                        TypeReference.makeTypeReference("address", true, false)
                    ));
                parameters.put("addVaultAndStrategy",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("address")

                    ));
                parameters.put("exit", Collections.emptyList());
                parameters.put("stake",
                    Collections.singletonList(
                        TypeReference.makeTypeReference("uint256")

                    ));
                parameters.put("migrateInOneTx",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("address")

                    ));
                parameters.put("Withdraw",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address", true, false),
                        TypeReference.makeTypeReference("uint256")
                    ));
                parameters.put("Deposit",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address", true, false),
                        TypeReference.makeTypeReference("uint256")
                    ));
                parameters.put("Invest",
                    Collections.singletonList(
                        TypeReference.makeTypeReference("uint256")
                    ));
                parameters.put("StrategyAnnounced",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint256")
                    ));
                parameters.put("StrategyChanged",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("address")
                    ));
                parameters.put("Staked",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address", true, false),
                        TypeReference.makeTypeReference("uint256")
                    ));
                parameters.put("Withdrawn",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address", true, false),
                        TypeReference.makeTypeReference("uint256")
                    ));
                parameters.put("RewardPaid",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address", true, false),
                        TypeReference.makeTypeReference("uint256")
                    ));
                parameters.put("RewardAdded",
                    Collections.singletonList(
                        TypeReference.makeTypeReference("uint256")
                    ));
                parameters.put("Migrated",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address", true, false),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256")
                    ));
                parameters.put("OwnershipTransferred",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address", true, false),
                        TypeReference.makeTypeReference("address", true, false)
                    ));
                parameters.put("Staked#V2",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address", true, false),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256")
                    ));
                parameters.put("Withdraw#V2",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address", true, false),
                        TypeReference.makeTypeReference("uint256", true, false),
                        TypeReference.makeTypeReference("uint256")
                    ));
                parameters.put("ProfitLogInReward",
                    Arrays.asList(
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256")
                    ));
                parameters.put("SharePriceChangeLog",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address", true, false),
                        TypeReference.makeTypeReference("address", true, false),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256")
                    ));
                parameters.put("Deposit#V2",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address", true, false),
                        TypeReference.makeTypeReference("uint256", true, false),
                        TypeReference.makeTypeReference("uint256")
                    ));
                parameters.put("Rewarded",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address", true, false),
                        TypeReference.makeTypeReference("address", true, false),
                        TypeReference.makeTypeReference("uint256")
                    ));
                parameters.put("underlyingBalanceInVault", Collections.emptyList());
                parameters.put("underlyingBalanceWithInvestment", Collections.emptyList());
                parameters.put("governance", Collections.emptyList());
                parameters.put("controller", Collections.emptyList());
                parameters.put("underlying", Collections.emptyList());
                parameters.put("strategy", Collections.emptyList());
                parameters.put("withdrawAll", Collections.emptyList());
                parameters.put("getPricePerFullShare", Collections.emptyList());
                parameters.put("doHardWork", Collections.emptyList());
                parameters.put("rebalance", Collections.emptyList());
                parameters.put("setStrategy",
                    Collections.singletonList(
                        TypeReference.makeTypeReference("address")

                    ));
                parameters.put("setVaultFractionToInvest",
                    Arrays.asList(
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256")

                    ));
                parameters.put("deposit",
                    Collections.singletonList(
                        TypeReference.makeTypeReference("uint256")

                    ));
                parameters.put("depositFor",
                    Arrays.asList(
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("address")

                    ));
                parameters.put("withdraw",
                    Collections.singletonList(
                        TypeReference.makeTypeReference("uint256")

                    ));
                parameters.put("underlyingBalanceWithInvestmentForHolder",
                    Collections.singletonList(
                        TypeReference.makeTypeReference("address")

                    ));
                parameters.put("depositAll",
                    Arrays.asList(
                        TypeReference.makeTypeReference("uint256[]"),
                        TypeReference.makeTypeReference("address[]")

                    ));
                parameters.put("approve",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint256")

                    ));
                parameters.put("Swap",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address", true, false),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("address", true, false)
                    ));
                parameters.put("Mint",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address", true, false),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256")
                    ));
                parameters.put("Burn",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address", true, false),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("address", true, false)
                    ));
                parameters.put("Sync",
                    Arrays.asList(
                        TypeReference.makeTypeReference("uint112"),
                        TypeReference.makeTypeReference("uint112")
                    ));
                parameters.put("Approval",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address", true, false),
                        TypeReference.makeTypeReference("address", true, false),
                        TypeReference.makeTypeReference("uint256")
                    ));
                parameters.put("Transfer",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address", true, false),
                        TypeReference.makeTypeReference("address", true, false),
                        TypeReference.makeTypeReference("uint256")
                    ));
                parameters.put("addLiquidity",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint256")

                    ));
                parameters.put("addLiquidityETH",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint256")

                    ));
                parameters.put("removeLiquidity",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint256")

                    ));
                parameters.put("removeLiquidityETH",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint256")

                    ));
                parameters.put("removeLiquidityWithPermit",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("bool"),
                        TypeReference.makeTypeReference("uint8"),
                        TypeReference.makeTypeReference("bytes32"),
                        TypeReference.makeTypeReference("bytes32")
                    ));
                parameters.put("removeLiquidityETHWithPermit",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("bool"),
                        TypeReference.makeTypeReference("uint8"),
                        TypeReference.makeTypeReference("bytes32"),
                        TypeReference.makeTypeReference("bytes32")
                    ));
                parameters.put("removeLiquidityETHSupportingFeeOnTransferTokens",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint256")
                    ));
                parameters.put("removeLiquidityETHWithPermitSupportingFeeOnTransferTokens",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("bool"),
                        TypeReference.makeTypeReference("uint8"),
                        TypeReference.makeTypeReference("bytes32"),
                        TypeReference.makeTypeReference("bytes32")
                    ));
                parameters.put("swapExactTokensForTokens",
                    Arrays.asList(
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("address[]"),
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint256")
                    ));
                parameters.put("swapTokensForExactTokens",
                    Arrays.asList(
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("address[]"),
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint256")
                    ));
                parameters.put("swapExactETHForTokens",
                    Arrays.asList(
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("address[]"),
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint256")
                    ));
                parameters.put("swapTokensForExactETH",
                    Arrays.asList(
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("address[]"),
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint256")
                    ));
                parameters.put("swapExactTokensForETH",
                    Arrays.asList(
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("address[]"),
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint256")
                    ));
                parameters.put("swapETHForExactTokens",
                    Arrays.asList(
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("address[]"),
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint256")
                    ));
                parameters.put("swapExactTokensForTokensSupportingFeeOnTransferTokens",
                    Arrays.asList(
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("address[]"),
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint256")
                    ));
                parameters.put("swapExactETHForTokensSupportingFeeOnTransferTokens",
                    Arrays.asList(
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("address[]"),
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint256")
                    ));
                parameters.put("swapExactTokensForETHSupportingFeeOnTransferTokens",
                    Arrays.asList(
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("address[]"),
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint256")
                    ));
                parameters.put("RewardDenied",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address", true, false),
                        TypeReference.makeTypeReference("uint256")
                    ));
                parameters.put("UpdateLiquidityLimit",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256")
                    ));
                parameters.put("transfer",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint256")
                    ));
                parameters.put("mint",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint256")
                    ));
                parameters.put("execute",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("bytes")
                    ));
                parameters.put("addMinter",
                    Collections.singletonList(
                        TypeReference.makeTypeReference("address")
                    ));
                parameters.put("allowance",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("address")
                    ));
                parameters.put("transferFrom",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint256")
                    ));
                parameters.put("increaseAllowance",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint256")
                    ));
                parameters.put("decreaseAllowance",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint256")
                    ));
                parameters.put("setStorage",
                    Collections.singletonList(
                        TypeReference.makeTypeReference("address")
                    ));
                parameters.put("getReward", Collections.emptyList());
                parameters.put("delegate", Collections.emptyList());
                parameters.put("swap",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("address[]"),
                        TypeReference.makeTypeReference("bytes"),
                        TypeReference.makeTypeReference("uint256[]"),
                        TypeReference.makeTypeReference("uint256[]")
                    ));
                parameters.put("ZapIn",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256")
                    ));
                parameters.put("bind",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint"),
                        TypeReference.makeTypeReference("uint")
                    ));
                parameters.put("rebind",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint"),
                        TypeReference.makeTypeReference("uint")
                    ));
                parameters.put("unbind",
                    Collections.singletonList(
                        TypeReference.makeTypeReference("address")
                    ));
                parameters.put("gulp",
                    Collections.singletonList(
                        TypeReference.makeTypeReference("address")
                    ));
                parameters.put("swapExactAmountIn",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint"),
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint"),
                        TypeReference.makeTypeReference("uint")
                    ));
                parameters.put("swapExactAmountOut",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint"),
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint"),
                        TypeReference.makeTypeReference("uint")
                    ));
                parameters.put("joinswapExternAmountIn",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint"),
                        TypeReference.makeTypeReference("uint")
                    ));
                parameters.put("joinswapPoolAmountOut",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint"),
                        TypeReference.makeTypeReference("uint")
                    ));
                parameters.put("exitswapPoolAmountIn",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint"),
                        TypeReference.makeTypeReference("uint")
                    ));
                parameters.put("exitswapExternAmountOut",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint"),
                        TypeReference.makeTypeReference("uint")
                    ));
                parameters.put("batchSwapExactIn",
                    Arrays.asList(
                        DynamicStructures.swapTypeReference(),
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint"),
                        TypeReference.makeTypeReference("uint")
                    ));
                parameters.put("batchSwapExactOut",
                    Arrays.asList(
                        DynamicStructures.swapTypeReference(),
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint")
                    ));
                parameters.put("batchEthInSwapExactIn",
                    Arrays.asList(
                        DynamicStructures.swapTypeReference(),
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint")
                    ));
                parameters.put("batchEthOutSwapExactIn",
                    Arrays.asList(
                        DynamicStructures.swapTypeReference(),
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint"),
                        TypeReference.makeTypeReference("uint")
                    ));
                parameters.put("batchEthInSwapExactOut",
                    Arrays.asList(
                        DynamicStructures.swapTypeReference(),
                        TypeReference.makeTypeReference("address")
                    ));
                parameters.put("batchEthOutSwapExactOut",
                    Arrays.asList(
                        DynamicStructures.swapTypeReference(),
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint")
                    ));
                parameters.put("doHardWork#V2",
                    Collections.singletonList(
                        TypeReference.makeTypeReference("address")
                    ));
                parameters.put("viewSplitExactOut",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint"),
                        TypeReference.makeTypeReference("uint")
                    ));
                parameters.put("viewSplitExactIn",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint"),
                        TypeReference.makeTypeReference("uint")
                    ));
                parameters.put("smartSwapExactOut",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address"), //TokenInterface
                        TypeReference.makeTypeReference("address"), //TokenInterface
                        TypeReference.makeTypeReference("uint"),
                        TypeReference.makeTypeReference("uint"),
                        TypeReference.makeTypeReference("uint")
                    ));
                parameters.put("smartSwapExactIn",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address"), //TokenInterface
                        TypeReference.makeTypeReference("address"), //TokenInterface
                        TypeReference.makeTypeReference("uint"),
                        TypeReference.makeTypeReference("uint"),
                        TypeReference.makeTypeReference("uint")
                    ));
                parameters.put("multihopBatchSwapExactOut",
                    Arrays.asList(
                        DynamicStructures.swapTypeReferenceDoubleArray(),
                        TypeReference.makeTypeReference("address"), //TokenInterface
                        TypeReference.makeTypeReference("address"), //TokenInterface
                        TypeReference.makeTypeReference("uint")
                    ));
                parameters.put("multihopBatchSwapExactIn",
                    Arrays.asList(
                        DynamicStructures.swapTypeReferenceDoubleArray(),
                        TypeReference.makeTypeReference("address"), //TokenInterface
                        TypeReference.makeTypeReference("address"), //TokenInterface
                        TypeReference.makeTypeReference("uint"),
                        TypeReference.makeTypeReference("uint")
                    ));
                parameters.put("notifyPoolsIncludingProfitShare",
                    Arrays.asList(
                        TypeReference.makeTypeReference("uint256[]"),
                        TypeReference.makeTypeReference("address[]"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256")
                    ));
                parameters.put("notifyProfitSharing", Collections.emptyList());
                parameters.put("provideLoan", Collections.emptyList());
                parameters.put("withdrawAllToVault", Collections.emptyList());
                parameters.put("tend", Collections.emptyList());
                parameters.put("harvest", Collections.emptyList());
                parameters.put("notifyPools",
                    Arrays.asList(
                        TypeReference.makeTypeReference("uint256[]"),
                        TypeReference.makeTypeReference("address[]"),
                        TypeReference.makeTypeReference("uint256")
                    ));
                parameters.put("poolNotifyFixedTarget",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("uint256")
                    ));
                parameters.put("sellToUniswap",
                    Arrays.asList(
                        TypeReference.makeTypeReference("address[]"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("uint256"),
                        TypeReference.makeTypeReference("bool")
                    ));
                parameters.put("executeMint",
                    Collections.singletonList(
                        TypeReference.makeTypeReference("uint256")
                    ));
                parameters.put(DeployerActivityEnum.SET_FEE_REWARD_FORWARDER.getMethodName(),
                    Collections.singletonList(
                        TypeReference.makeTypeReference("address")
                    ));
                parameters.put(DeployerActivityEnum.SET_REWARD_DISTRIBUTION.getMethodName(),
                    Collections.singletonList(
                        TypeReference.makeTypeReference("address")
                    ));
                parameters.put(DeployerActivityEnum.SET_PATH.getMethodName(),
                    Arrays.asList(
                        TypeReference.makeTypeReference("bytes32"),
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("address[]")
                    ));
                parameters.put(DeployerActivityEnum.SET_LIQUIDITY_LOAN_TARGET.getMethodName(),
                    Collections.singletonList(
                        TypeReference.makeTypeReference("uint256")
                    ));
                parameters.put(DeployerActivityEnum.SETTLE_LOAN.getMethodName(),
                    Collections.singletonList(
                        TypeReference.makeTypeReference("uint256")
                    ));
                parameters.put(DeployerActivityEnum.ADD_DEX.getMethodName(),
                    Arrays.asList(
                        TypeReference.makeTypeReference("bytes32"),
                        TypeReference.makeTypeReference("address")
                    ));
                parameters.put(DeployerActivityEnum.SET_CONTROLLER.getMethodName(),
                    Collections.singletonList(
                        TypeReference.makeTypeReference("address")
                    ));
                parameters.put(DeployerActivityEnum.SET_HARD_REWARDS.getMethodName(),
                    Collections.singletonList(
                        TypeReference.makeTypeReference("address")
                    ));
                parameters.put(DeployerActivityEnum.ADD_VAULT.getMethodName(),
                    Collections.singletonList(
                        TypeReference.makeTypeReference("address")
                    ));
                parameters.put(DeployerActivityEnum.SET_TOKEN_POOL.getMethodName(),
                    Collections.singletonList(
                        TypeReference.makeTypeReference("address")
                    ));
                parameters.put(DeployerActivityEnum.SET_OPERATOR.getMethodName(),
                    Collections.singletonList(
                        TypeReference.makeTypeReference("address")
                    ));
                parameters.put(DeployerActivityEnum.SET_TEAM.getMethodName(),
                    Collections.singletonList(
                        TypeReference.makeTypeReference("address")
                    ));
                parameters.put(DeployerActivityEnum.NOTIFY_REWARD_AMOUNT.getMethodName(),
                    Collections.singletonList(
                        TypeReference.makeTypeReference("uint256")
                    ));
                parameters.put(DeployerActivityEnum.RENOUNCE_MINTER.getMethodName(), Collections.emptyList());
                parameters.put(DeployerActivityEnum.ADD_HARD_WORKER.getMethodName(),
                    Collections.singletonList(
                        TypeReference.makeTypeReference("address")
                    ));
                parameters.put(DeployerActivityEnum.SET_CONVERSION_PATH.getMethodName(),
                    Arrays.asList(
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("address"),
                        TypeReference.makeTypeReference("address[]")
                    ));
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            }
            writeParameters(parameters);
        }
    }

    public Map<String, String> getMethodNamesByMethodId() {
        return methodNamesByMethodId;
    }
}
