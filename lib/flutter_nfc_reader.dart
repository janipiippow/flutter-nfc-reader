import 'dart:async';

import 'package:flutter/services.dart';

enum NFCStatus {
  none,
  reading,
  read,
  stopped,
  error,
}

enum MessageType {
  NDEF,
}

abstract class NFCMessage {
  String get id;
  MessageType get messageType;
}

class NDEFMessage implements NFCMessage {
  String id;
  final String type;
  final List<NDEFRecord> records;

  NDEFMessage({this.type, this.records, this.id});

  // payload returns the contents of the first non-empty record. If all records
  // are empty it will return null.
  String get payload {
    for (var record in records) {
      if (record.payload != "") {
        return record.payload;
      }
    }
    return null;
  }

  @override
  MessageType get messageType => MessageType.NDEF;
  
  static NDEFMessage fromDataMap(dynamic map) {
    Map<String, dynamic> data = new Map<String, dynamic>.from(map);
    List<dynamic> records = data['records'];
    return NDEFMessage(
      id: data['id'],
      type: data['type'],
      records: records.map((record) => NDEFRecord.fromData(record)).toList()
    );
  } 
}

class NDEFRecord {
  final String id;
  final String payload;
  final String type;

  /// tnf is only available on Android
  final int tnf;

  NDEFRecord({this.id, this.payload, this.type, this.tnf});

  static NDEFRecord fromData(dynamic data) {
    return NDEFRecord(
      id: data['id'],
      type: data['type'],
      payload: data['payload'],
      tnf: null
    );
  }
}

class NfcData {
  final String id;
  final NDEFMessage content;
  final String error;
  final String statusMapper;

  NFCStatus status;

  NfcData({
    this.id,
    this.content,
    this.error,
    this.statusMapper,
  });

  factory NfcData.fromMap(Map data) {
    NfcData result = NfcData(
      id: data['nfcId'],
      content: NDEFMessage.fromDataMap(data['nfcContent']),
      error: data['nfcError'],
      statusMapper: data['nfcStatus'],
    );
    switch (result.statusMapper) {
      case 'none':
        result.status = NFCStatus.none;
        break;
      case 'reading':
        result.status = NFCStatus.reading;
        break;
      case 'stopped':
        result.status = NFCStatus.stopped;
        break;
      case 'error':
        result.status = NFCStatus.error;
        break;
      default:
        result.status = NFCStatus.none;
    }
    return result;
  }
}

class FlutterNfcReader {
  static const MethodChannel _channel =
      const MethodChannel('flutter_nfc_reader');
  static const stream =
      const EventChannel('it.matteocrippa.flutternfcreader.flutter_nfc_reader');

 static Future<NfcData>  stop() async{
    final Map data = await _channel.invokeMethod('NfcStop');
    final NfcData result = NfcData.fromMap(data);

    return result;
  }

  static Future<NfcData>  read() async{
    final Map data = await _channel.invokeMethod('NfcRead');
    final NfcData result = NfcData.fromMap(data);

    return result;
  }

  static Future<NfcData> write(String path,String label) async {
    final Map data = await _channel.invokeMethod('NfcWrite',<String,dynamic>{'label':label,'path':path});

    final NfcData result = NfcData.fromMap(data);

    return result;
  }


}
