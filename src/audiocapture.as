package {
    import flash.display.LoaderInfo;
    import flash.display.Sprite;
    import flash.events.SampleDataEvent;
    import flash.events.StatusEvent;
    import flash.external.ExternalInterface;
    import flash.media.Microphone;
    import flash.media.SoundCodec;
    import flash.utils.ByteArray;

    public class audiocapture extends Sprite {
        public var failHandler:String = null;
        public var samplesHandler:String = null;
        public var mic:Microphone = null;

        public function audiocapture() {
            var parameters:* = LoaderInfo(this.root.loaderInfo).parameters;
            failHandler = parameters.fail;
            samplesHandler = parameters.samples;

            mic = Microphone.getMicrophone();
            if( mic != null ){
                mic.setSilenceLevel(0, 4000);
                mic.setLoopBack(false);
                mic.rate = 44;
                mic.addEventListener(SampleDataEvent.SAMPLE_DATA, streamHandler);
                mic.addEventListener(StatusEvent.STATUS, statusHandler);
            } else if (Microphone.isSupported === false) {
                ExternalInterface.call(failHandler, "Microphone usage is not supported");
            } else {
                ExternalInterface.call(failHandler, "No microphone detected");
            }
        };

        public function streamHandler(event:SampleDataEvent):void {
            var data:* = new Array();
            while (event.data.bytesAvailable) {
                data.push(event.data.readFloat());
            }
            ExternalInterface.call(samplesHandler, data);
        };

        public function statusHandler(event:StatusEvent):void {
            if(event.code == "Microphone.Unmuted"){
            } else if( event.code == "Microphone.Muted" ) {
                ExternalInterface.call(failHandler, "Microphone access denied");
            }
        };
    }
}
