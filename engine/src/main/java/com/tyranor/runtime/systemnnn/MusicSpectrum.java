package com.tyranor.runtime.systemnnn;

/** FFT over the preview track's PCM window. All scratch buffers stay on the runner thread. */
final class MusicSpectrum {
    final float[] wave;
    private final double[] real,imag,window;
    private final int[] starts;
    MusicSpectrum(GameUiAssets.Config cfg,int rate){
        int n=Integer.highestOneBit(Math.max(64,Math.min(1024,cfg.number("spectrumCalcuPoint",256))));
        int bands=Math.max(1,Math.min(64,cfg.number("spectrumPrintNumber",16)));
        wave=new float[n];real=new double[n];imag=new double[n];window=new double[n];starts=new int[bands+1];
        int lo=Math.max(1,Math.min(rate/2,cfg.number("spectrumStartHz",200)));
        int hi=Math.max(lo,Math.min(rate/2,cfg.number("spectrumEndHz",10000)));
        for(int i=0;i<n;i++)window[i]=.5-.5*Math.cos(4*Math.PI*i/n);
        for(int i=0;i<starts.length;i++)starts[i]=Math.min(n/2-1,(int)(lo*Math.pow(hi/(double)lo,i/(double)bands)*n/rate));
    }
    int[] levels(){
        int n=wave.length,bits=Integer.numberOfTrailingZeros(n);
        for(int i=0;i<n;i++){
            int at=Integer.reverse(i) >>> (32-bits);
            real[at]=wave[i]*window[i];imag[at]=0;
        }
        for(int size=2;size<=n;size*=2){
            double angle=-2*Math.PI/size,stepR=Math.cos(angle),stepI=Math.sin(angle);
            for(int first=0;first<n;first+=size){
                double wr=1,wi=0;
                for(int j=0;j<size/2;j++){
                    int a=first+j,b=a+size/2;
                    double r=wr*real[b]-wi*imag[b],im=wr*imag[b]+wi*real[b];
                    real[b]=real[a]-r;imag[b]=imag[a]-im;real[a]+=r;imag[a]+=im;
                    double next=wr*stepR-wi*stepI;wi=wr*stepI+wi*stepR;wr=next;
                }
            }
        }
        int[] out=new int[starts.length-1];
        for(int i=0;i<out.length;i++){
            int from=starts[i],to=Math.max(from+1,starts[i+1]);
            double sum=0;
            for(int j=from;j<to;j++)sum+=Math.max(-100,20*Math.log10(Math.hypot(real[j],imag[j])*2/n+.00001));
            out[i]=Math.max(0,Math.min(100,(int)(100+sum/(to-from))));
        }
        return out;
    }
}
