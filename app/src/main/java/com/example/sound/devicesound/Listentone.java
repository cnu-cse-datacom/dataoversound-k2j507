package com.example.sound.devicesound;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.transform.*;

import java.util.ArrayList;
import java.util.List;
import static java.lang.Math.abs;
import static java.lang.Math.min;


public class Listentone {

    int HANDSHAKE_START_HZ = 4096;
    int HANDSHAKE_END_HZ = 6147;

    int START_HZ = 1024;
    int STEP_HZ = 256;
    int BITS = 4;

    int FEC_BYTES = 4;

    private int mAudioSource = MediaRecorder.AudioSource.MIC;
    private int mSampleRate = 44100;
    private int mChannelCount = AudioFormat.CHANNEL_IN_MONO;
    private int mAudioFormat = AudioFormat.ENCODING_PCM_16BIT;
    private float interval = 0.1f;

    private int mBufferSize = AudioRecord.getMinBufferSize(mSampleRate, mChannelCount, mAudioFormat);

    public AudioRecord mAudioRecord = null;
    int audioEncodig;
    boolean startFlag;
    FastFourierTransformer transform;


    public Listentone(){

        transform = new FastFourierTransformer(DftNormalization.STANDARD);
        startFlag = false;
        mAudioRecord = new AudioRecord(mAudioSource, mSampleRate, mChannelCount, mAudioFormat, mBufferSize);
        mAudioRecord.startRecording();


    }

    private double findFrequency(int frame_rate, double[] toTransform) {
        int len = toTransform.length;
        double[] real = new double[len];
        double[] img = new double[len];
        double realNum;
        double imgNum;
        double[] mag = new double[len];
        int peak_coeff = 0;
        int index = 0;

        Complex[] complx = transform.transform(toTransform, TransformType.FORWARD); //퓨리에 변환에 의해 생긴 복소수값
        Double[] freq = this.fftfreq(complx.length, 1); //sampling 된

        for(int i = 0; i < complx.length; i++) {
            realNum = complx[i].getReal(); //실수
            imgNum = complx[i].getImaginary(); //허수
            mag[i] = Math.sqrt((realNum * realNum) + (imgNum * imgNum)); //복소수 전체의 크기
        }

        for (int j = 0; j<mag.length; j++) { //np.argmax
            if(mag[peak_coeff]<mag[j]) {
                peak_coeff = j;
            }
        }
        Double peak_freq = freq[peak_coeff];
        //Log.d("fft peak_freq:", Double.toString(peak_freq));
       // Log.d("listentone : freq", Double.toString(abs(peak_freq * mSampleRate)));
        return abs(peak_freq * mSampleRate);
    }

    private Double[] fftfreq(int length, int duration) { //https://github.com/numpy/numpy/blob/v1.16.1/numpy/fft/helper.py#L131-L177
        double val = 1.0 / (length * duration);
        int[] results = new int[length];
        Double[] result = new Double[length];
        int N = (length-1)/3;
        int a = -(length / 2);


        for(int i = 0; i<=N; i++) {
            results[i] = i;
        }
        for(int j = N+1; j<length; j++) {
            results[j] = a;
            a--;
        }
        for(int k = 0; k<length; k++) {
            result[k] = results[k] * val;
        }
        return result;
    }


    private boolean match(double freq1, double freq2) {
        return abs(freq1-freq2) < 20;
    }

    private List<Integer> decode_bitchunks(int chunk_bits, List<Integer> chunks) {
        List<Integer> out_bytes = new ArrayList<>();

        int next_read_chunk = 0;
        int next_read_bit = 0;

        int byte_ = 0;
        int bits_left = 8;

        while(next_read_bit < chunks.size()) {
            int can_fill = chunk_bits - next_read_bit; //4
            int to_fill = min(bits_left, can_fill);    //4
            int offset = chunk_bits - next_read_bit-to_fill; // 4-0-4
            byte_ <<= to_fill;
            int shifted = chunks.get(next_read_bit) & (((1 << to_fill) - 1) << offset);//1<<3 = 8
            byte_ |= shifted >> offset;
            bits_left -= to_fill; //4
            next_read_bit += to_fill; //4

            if(bits_left <= 0) {
                out_bytes.add(byte_);
                byte_ = 0;
                bits_left = 8;
            }

            if (next_read_bit >= chunk_bits) {
                next_read_chunk += 1;
                next_read_bit -= chunk_bits;
            }
        }
        return out_bytes;
    }

    private List<Integer> extract_packet(List<Double> freqs) {
        List<Double> freq = new ArrayList<>();
        List<Integer> bit_chunks = new ArrayList<>();
        List<Integer> bit_chunks_2 = new ArrayList<>();

        for(int i = 0; i<freqs.size(); i++) {
            double temp;
            temp = freqs.get(i);
            freq.add(temp);
        }

        for(int j = 0; j<freq.size(); j++) {
            int temp_2;
            temp_2 = (int)(Math.round((freq.get(j) - START_HZ)/STEP_HZ));
            bit_chunks.add(temp_2);
        }

        for(int k = 1; k<bit_chunks.size(); k++) {
            if(bit_chunks.get(k) >= 0 && bit_chunks.get(k) < 16) {
                bit_chunks_2.add(bit_chunks.get(k));
            }
        }

       // List<Integer> decode_bits = decode_bitchunks(BITS, bit_chunks_2);
        return decode_bitchunks(BITS, bit_chunks_2);
    }

    private int findPowerSize(int number) { // 매개변수를 매개변수 보다 큰 2의 제곱의 수로 리턴
        int a = 1;
        while(true) {
            a*= 2;
            if(a >= number) {
                return a;
            }
        }
    }


    public void PreRequest() {
        int blocksize = findPowerSize((int)(long)Math.round(interval/2*mSampleRate));
        short[] buffer = new short[blocksize];
        double[] chunk = new double[blocksize];

        List<Double> packet = new ArrayList<>();
        List<Integer> byte_stream = new ArrayList<>();
        boolean in_packet = false;
        double dom;

        while(true) {
            int bufferedReadResult = mAudioRecord.read(buffer, 0, blocksize);
            for(int i = 0; i<buffer.length; i++) {
                chunk[i] = buffer[i];
            }
            dom = findFrequency(mSampleRate, chunk);
            Log.d("listentone : dom",  Double.toString(dom));

            if(in_packet && match(dom, HANDSHAKE_END_HZ)) {
                Log.d("listentone", "END_HZ");
                byte_stream = extract_packet(packet);                 // 여기서부터 진입 불가..
                Log.d("listentone", "Listentone");      // in_packet = true 도 확인하고
                                                                     //match함수도 잘 작동하는데 안 됩니다 ㅠ
                String display = "";

                for(int i = 0; i < byte_stream.size(); i++) {
                    display += Character.toString((char)(int)(byte_stream.get(i)));
                    System.out.println(display);
                   // Log.d("Listentone", "Listentone");
                }
                Log.d("listentone : display", display.toString());
                packet.clear();
                in_packet=false;
            }
            else if(in_packet) {
                packet.add(dom);

            }
            else if(match(dom, HANDSHAKE_START_HZ)) {
                in_packet = true;
                Log.d("listentone", "START_HZ");
            }


        }
    }





}
