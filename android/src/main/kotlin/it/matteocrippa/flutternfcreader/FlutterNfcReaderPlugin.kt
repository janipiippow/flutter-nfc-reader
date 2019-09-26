package it.matteocrippa.flutternfcreader

import android.Manifest
import android.annotation.TargetApi
import android.content.Context
import android.content.Intent
import android.nfc.*
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Build
import android.os.Handler
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry.Registrar
import java.nio.charset.Charset
import android.os.Looper
import java.io.IOException
import java.math.BigInteger
import java.nio.charset.StandardCharsets


const val PERMISSION_NFC = 1007

class FlutterNfcReaderPlugin(val registrar: Registrar) : MethodCallHandler, EventChannel.StreamHandler, NfcAdapter.ReaderCallback {

    private val activity = registrar.activity()

    private var nfcAdapter: NfcAdapter? = null
    private var nfcManager: NfcManager? = null


    private var kId = "nfcId"
    private var kContent = "nfcContent"
    private var kError = "nfcError"
    private var kStatus = "nfcStatus"
    private var kWrite = ""
    private var kPath = ""
    private var readResult :Result? = null
    private var writeResult :Result? = null

    private var READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_BARCODE or
            NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V
            
    companion object {
        @JvmStatic
        fun registerWith(registrar: Registrar): Unit {
            val messenger = registrar.messenger()
            val channel = MethodChannel(messenger, "flutter_nfc_reader")
            val eventChannel = EventChannel(messenger, "it.matteocrippa.flutternfcreader.flutter_nfc_reader")
            val plugin = FlutterNfcReaderPlugin(registrar)
            channel.setMethodCallHandler(plugin)
            eventChannel.setStreamHandler(plugin)
        }
    }

    init {
        nfcManager = activity.getSystemService(Context.NFC_SERVICE) as? NfcManager
        nfcAdapter = nfcManager?.defaultAdapter

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            activity.requestPermissions(
                    arrayOf(Manifest.permission.NFC),
                    PERMISSION_NFC
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            nfcAdapter?.enableReaderMode(activity, this, READER_FLAGS, null )
        }


    }

    private fun writeMessageToTag(nfcMessage: NdefMessage, tag: Tag?): Boolean {

        try {
            val nDefTag = Ndef.get(tag)

            nDefTag?.let {
                it.connect()
                if (it.maxSize < nfcMessage.toByteArray().size) {
                    //Message to large to write to NFC tag
                    return false
                }
                if (it.isWritable) {
                    it.writeNdefMessage(nfcMessage)
                    it.close()
                    //Message is written to tag
                    return true
                } else {
                    //NFC tag is read-only
                    return false
                }
            }

            val nDefFormatableTag = NdefFormatable.get(tag)

            nDefFormatableTag?.let {
                try {
                    it.connect()
                    it.format(nfcMessage)
                    it.close()
                    //The data is written to the tag
                    return true
                } catch (e: IOException) {
                    //Failed to format tag
                    return false
                }
            }
            //NDEF is not supported
            return false

        } catch (e: Exception) {
            //Write operation has failed
        }
        return false
    }

    fun createNFCMessage(payload: String?, intent: Intent?) : Boolean {

        val pathPrefix = "it.matteocrippa.flutternfcreader"
        val nfcRecord = NdefRecord(NdefRecord.TNF_EXTERNAL_TYPE, pathPrefix.toByteArray(), ByteArray(0), (payload as String).toByteArray())
        val nfcMessage = NdefMessage(arrayOf(nfcRecord))
        intent?.let {
            val tag = it.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
            return  writeMessageToTag(nfcMessage, tag)
        }
        return false
    }


    override fun onMethodCall(call: MethodCall, result: Result): Unit {

        when (call.method) {
            "NfcStop" -> {
                readResult=null
                writeResult=null
            }

            "NfcRead" -> {

                if (!nfcAdapter?.isEnabled!!) {
                    result.error("404", "NFC Hardware not found", null)
                    return
                }else{
                    readResult=result
                }

            }
            "NfcWrite"->{


                if (!nfcAdapter?.isEnabled!!) {
                    result.error("404", "NFC Hardware not found", null)
                    return
                }else {
                    writeResult = result
                    kWrite = call.argument("label")!!
                    kPath = call.argument("path")!!
                }


            }

            else -> {
                result.notImplemented()
            }
        }
    }

    // EventChannel.StreamHandler methods
    override fun onListen(arguments: Any?, eventSink: EventChannel.EventSink?) {
    }

    override fun onCancel(arguments: Any?) {
    }



    private fun stopNFC() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            nfcAdapter?.disableReaderMode(activity)
        }
    }

    // handle discovered NDEF Tags
    override fun onTagDiscovered(tag: Tag?) {
        if(writeResult!=null){
            val nfcRecord = NdefRecord(NdefRecord.TNF_EXTERNAL_TYPE, kPath.toByteArray(), ByteArray(0), kWrite.toByteArray())
            val nfcMessage = NdefMessage(arrayOf(nfcRecord))
            writeMessageToTag(nfcMessage, tag)
            val data = mapOf(kId to "", kContent to kWrite, kError to "", kStatus to "write")
            val mainHandler = Handler(Looper.getMainLooper())
            mainHandler.post {
                writeResult?.success(data)
                writeResult=null;
            }
        }
       /* */

        if(readResult!=null){
            // convert tag to NDEF tag
            val ndef = Ndef.get(tag)
            ndef?.connect()
            val ndefMessage : NdefMessage? = ndef?.ndefMessage?: ndef?.cachedNdefMessage

            val message = formatNDEFMessageToResult(ndef, ndefMessage)

            //val message = ndefMessage?.toByteArray()
            //        ?.toString(Charset.forName("UTF-8")) ?: ""
            //val id = tag?.id?.toString(Charset.forName("ISO-8859-1")) ?: ""
            val id = bytesToHexString(tag?.id) ?: ""
            ndef?.close()
            if (message != null) {
                val data = mapOf(kId to id, kContent to message, kError to "", kStatus to "reading")
                val mainHandler = Handler(Looper.getMainLooper())
                mainHandler.post {
                    readResult?.success(data)
                    readResult=null;
                }
            }

        }

    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    private fun formatNDEFMessageToResult(ndef: Ndef, message: NdefMessage?): Map<String, Any> {
        val result = HashMap<String, Any>()
        val records = ArrayList<HashMap<String, String>>()
        if(message != null) {
            for (record in message.getRecords()) {
                val recordMap = HashMap<String, String>()
                recordMap.put("payload", String(record.getPayload(), StandardCharsets.UTF_8))
                recordMap.put("id", String(record.getId(), StandardCharsets.UTF_8))
                recordMap.put("type", String(record.getType(), StandardCharsets.UTF_8))
                // recordMap.put("tnf",  String.valueOf(record.getTnf()))
                records.add(recordMap)
            }
            val idByteArray = ndef.getTag().getId()
            // Fancy string formatting snippet is from
            // https://gist.github.com/luixal/5768921#gistcomment-1788815
            result.put("id", String.format("%0" + idByteArray.size * 2 + "X", BigInteger(1, idByteArray)))
            result.put("message_type", "ndef")
            result.put("type", ndef.getType())
            result.put("records", records)
        }

        return result
    }


    private fun bytesToHexString(src: ByteArray?): String? {
        val stringBuilder = StringBuilder("0x")
        if (src == null || src.isEmpty()) {
            return null
        }

        val buffer = CharArray(2)
        for (i in src.indices) {
            buffer[0] = Character.forDigit(src[i].toInt().ushr(4).and(0x0F), 16)
            buffer[1] = Character.forDigit(src[i].toInt().and(0x0F), 16)
            stringBuilder.append(buffer)
        }

        return stringBuilder.toString()
    }
}