package com.fit2cloud.provider.impl.aliyun.api;

import com.aliyun.sdk.service.oss20190517.AsyncClient;
import com.aliyun.sdk.service.oss20190517.models.*;
import com.fit2cloud.common.constants.PlatformConstants;
import com.fit2cloud.common.exception.Fit2cloudException;
import com.fit2cloud.common.platform.credential.Credential;
import com.fit2cloud.common.provider.util.PageUtil;
import com.fit2cloud.common.util.CsvUtil;
import com.fit2cloud.constants.BillingSettingConstants;
import com.fit2cloud.es.entity.CloudBill;
import com.fit2cloud.provider.impl.aliyun.entity.credential.AliyunBillCredential;
import com.fit2cloud.provider.impl.aliyun.entity.csv.AliBillCsvModel;
import com.fit2cloud.provider.impl.aliyun.entity.request.ListBucketMonthRequest;
import com.fit2cloud.provider.impl.aliyun.entity.request.SyncBillRequest;
import com.fit2cloud.provider.impl.aliyun.uitil.AliyunMappingUtil;
import jodd.util.StringUtil;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AliBucketApi {
    //1922504452814088_BillingItemDetailMonthly_202201
    private final static Pattern monthPattern = Pattern.compile("BillingItemDetailMonthly_\\d{4}");

    /**
     * 获取所有的桶
     *
     * @param aliyunBillCredential 阿里云认证信息
     * @return 获取所有的桶
     */
    public static List<Bucket> listBucket(AliyunBillCredential aliyunBillCredential) {
        AsyncClient ossClient = aliyunBillCredential.getOssClient();
        ListBucketsRequest listBucketsRequest = ListBucketsRequest.builder().maxKeys(100l).build();
        return PageUtil.pageNextF(listBucketsRequest,
                ossClient::listBuckets,
                res -> res.join().getBody().getBuckets(),
                (req, res) -> StringUtil.isNotEmpty(res.join().getBody().getNextMarker()),
                (req, res) -> ListBucketsRequest.builder().marker(res.join().getBody().getNextMarker()).maxKeys(100l).build());

    }

    /**
     * 获取一个桶
     *
     * @param aliyunBillCredential 阿里云认证信息
     * @param regionId             区域id
     * @param bucketName           桶名称
     * @return 阿里云桶对象
     */
    public static Bucket getBucket(AliyunBillCredential aliyunBillCredential, String bucketName, String regionId) {
        AsyncClient ossClient = aliyunBillCredential.getOssClient();
        ListBucketsRequest listBucketsRequest = ListBucketsRequest.builder().prefix(bucketName).maxKeys(100l).build();
        ListBucketsResponse listBucketsResponse = ossClient.listBuckets(listBucketsRequest).join();
        List<Bucket> buckets = listBucketsResponse.getBody().getBuckets();
        if (CollectionUtils.isNotEmpty(buckets)) {
            return buckets.stream()
                    .filter(bucket -> StringUtils.equals(regionId, bucket.getRegion()) && bucket.getName().equals(bucketName))
                    .findAny().orElseThrow(() -> new Fit2cloudException(222, "不存在的桶"));
        }
        throw new Fit2cloudException(222, "不存在的桶");
    }

    /**
     * 获取桶中所有文件
     *
     * @param aliBillCredential 阿里云认证
     * @param bucketName        桶名称
     * @param regionId          区域id
     * @return 文件对象
     */
    public static List<ObjectSummary> listObjectSummary(AliyunBillCredential aliBillCredential, String bucketName, String regionId) {
        return listObjectSummary(aliBillCredential, bucketName);
    }

    /**
     * 获取桶中的文件对象
     *
     * @param aliBillCredential 阿里云认证信息
     * @param bucketName        桶名称
     * @return 存储桶中所有的文件
     */
    public static List<ObjectSummary> listObjectSummary(AliyunBillCredential aliBillCredential, String bucketName) {
        AsyncClient ossClient = aliBillCredential.getOssClient();
        return listObjectSummary(ossClient, bucketName);
    }

    /**
     * 获取桶中的文件对象
     *
     * @param ossClient  阿里云对象存储客户端
     * @param bucketName 桶名称
     * @return 存储桶中所有的文件
     */
    public static List<ObjectSummary> listObjectSummary(AsyncClient ossClient, String bucketName) {
        ListObjectsV2Request listObjectsRequest = ListObjectsV2Request.builder().bucket(bucketName).maxKeys(100l).build();
        return PageUtil.pageNextF(listObjectsRequest, ossClient::listObjectsV2
                , res -> {
                    List<ObjectSummary> contents = res.join().getBody().getContents();
                    if (CollectionUtils.isEmpty(contents)) {
                        return new ArrayList<>();
                    }
                    return contents;
                }
                , (req, res) -> StringUtils.isNotEmpty(res.join().getBody().getNextContinuationToken())
                , (req, res) -> ListObjectsV2Request.builder().bucket(bucketName).maxKeys(100l).continuationToken(res.join().getBody().getNextContinuationToken()).build());
    }

    /**
     * 获取制定月份账单数据
     *
     * @param syncBillRequest 请求对象
     * @return 云管账单数据
     */
    public static List<CloudBill> listBill(SyncBillRequest syncBillRequest) {
        AsyncClient ossClient = syncBillRequest.getCredential().getOssClient();
        List<ObjectSummary> objectSummaryList = listObjectSummary(ossClient, syncBillRequest.getBill().getBucketId());
        // todo 获取阿里云桶账单文件并且读取
        List<AliBillCsvModel> aliBillCsvModels = objectSummaryList.stream()
                .filter((objectSummary) -> objectSummary.getKey().endsWith("BillingItemDetailMonthly_" + syncBillRequest.getBillingCycle().replace("-", "")))
                .findFirst()
                .map(objectSummary -> writeAndReadFile(ossClient, objectSummary, syncBillRequest.getBill().getBucketId(), syncBillRequest.getCloudAccountId()))
                .orElse(new ArrayList<>());
        List<Credential.Region> regions = syncBillRequest.getCredential().regions();
        // todo 转换为系统账单对象
        return aliBillCsvModels.stream().map(aliBillCsvModel -> AliyunMappingUtil.toCloudBill(aliBillCsvModel, regions)).toList();
    }

    private static List<AliBillCsvModel> writeAndReadFile(AsyncClient ossClient, ObjectSummary objectSummary, String bucketName, String cloudAccountId) {
        // 写入数据
        File file = CsvUtil.writeFile(BillingSettingConstants.billingPath + File.separator + PlatformConstants.fit2cloud_ali_platform.name() + File.separator + cloudAccountId, objectSummary.getKey(), objectSummary.getSize(),
                () -> ossClient.getObject(GetObjectRequest.builder().bucket(bucketName).key(objectSummary.getKey()).build()).join().getBody());
        return CsvUtil.parse(file, AliBillCsvModel.class);
    }

    public static List<String> listBucketFileMonth(ListBucketMonthRequest listBucketMonthRequest) {
        List<ObjectSummary> objectSummaryList = listObjectSummary(listBucketMonthRequest.getCredential(), listBucketMonthRequest.getBill().getBucketId());
        return objectSummaryList.stream().filter(objectSummary -> monthPattern.matcher(objectSummary.getKey()).find()).map(ObjectSummary::getKey).map(fileName -> {
            String month = fileName.substring(fileName.length() - 6);
            return month.substring(0, 4) + "-" + month.substring(4, 6);
        }).distinct().toList();
    }
}
