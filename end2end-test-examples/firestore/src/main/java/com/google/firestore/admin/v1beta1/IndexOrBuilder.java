// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: google/firestore/admin/v1beta1/index.proto

package com.google.firestore.admin.v1beta1;

public interface IndexOrBuilder extends
    // @@protoc_insertion_point(interface_extends:google.firestore.admin.v1beta1.Index)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * The resource name of the index.
   * Output only.
   * </pre>
   *
   * <code>string name = 1;</code>
   */
  java.lang.String getName();
  /**
   * <pre>
   * The resource name of the index.
   * Output only.
   * </pre>
   *
   * <code>string name = 1;</code>
   */
  com.google.protobuf.ByteString
      getNameBytes();

  /**
   * <pre>
   * The collection ID to which this index applies. Required.
   * </pre>
   *
   * <code>string collection_id = 2;</code>
   */
  java.lang.String getCollectionId();
  /**
   * <pre>
   * The collection ID to which this index applies. Required.
   * </pre>
   *
   * <code>string collection_id = 2;</code>
   */
  com.google.protobuf.ByteString
      getCollectionIdBytes();

  /**
   * <pre>
   * The fields to index.
   * </pre>
   *
   * <code>repeated .google.firestore.admin.v1beta1.IndexField fields = 3;</code>
   */
  java.util.List<com.google.firestore.admin.v1beta1.IndexField> 
      getFieldsList();
  /**
   * <pre>
   * The fields to index.
   * </pre>
   *
   * <code>repeated .google.firestore.admin.v1beta1.IndexField fields = 3;</code>
   */
  com.google.firestore.admin.v1beta1.IndexField getFields(int index);
  /**
   * <pre>
   * The fields to index.
   * </pre>
   *
   * <code>repeated .google.firestore.admin.v1beta1.IndexField fields = 3;</code>
   */
  int getFieldsCount();
  /**
   * <pre>
   * The fields to index.
   * </pre>
   *
   * <code>repeated .google.firestore.admin.v1beta1.IndexField fields = 3;</code>
   */
  java.util.List<? extends com.google.firestore.admin.v1beta1.IndexFieldOrBuilder> 
      getFieldsOrBuilderList();
  /**
   * <pre>
   * The fields to index.
   * </pre>
   *
   * <code>repeated .google.firestore.admin.v1beta1.IndexField fields = 3;</code>
   */
  com.google.firestore.admin.v1beta1.IndexFieldOrBuilder getFieldsOrBuilder(
      int index);

  /**
   * <pre>
   * The state of the index.
   * Output only.
   * </pre>
   *
   * <code>.google.firestore.admin.v1beta1.Index.State state = 6;</code>
   */
  int getStateValue();
  /**
   * <pre>
   * The state of the index.
   * Output only.
   * </pre>
   *
   * <code>.google.firestore.admin.v1beta1.Index.State state = 6;</code>
   */
  com.google.firestore.admin.v1beta1.Index.State getState();
}