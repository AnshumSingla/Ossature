Add-Type -AssemblyName System.Speech;$s=New-Object System.Speech.Synthesis.SpeechSynthesizer;$f=New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000,[System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen,[System.Speech.AudioFormat.AudioChannel]::Mono);$s.Rate=0
$s.GetInstalledVoices()|%{$_.VoiceInfo.Name}
$s.SetOutputToWaveFile('audio\p00.wav',$f);$s.Speak('I would put a load balancer in front of several stateless API servers.');$s.SetOutputToNull();
$s.SetOutputToWaveFile('audio\p01.wav',$f);$s.Speak('We can shard the database by user id and use consistent hashing to spread the load.');$s.SetOutputToNull();
$s.SetOutputToWaveFile('audio\p02.wav',$f);$s.Speak('Reads are cached in Redis with a time to live of five minutes.');$s.SetOutputToNull();
$s.SetOutputToWaveFile('audio\p03.wav',$f);$s.Speak('To keep the write path idempotent the client sends an idempotency key with every request.');$s.SetOutputToNull();
$s.SetOutputToWaveFile('audio\p04.wav',$f);$s.Speak('Kafka gives us a durable log, and the consumers process events at least once.');$s.SetOutputToNull();
$s.SetOutputToWaveFile('audio\p05.wav',$f);$s.Speak('A content delivery network serves the static assets close to the user.');$s.SetOutputToNull();
$s.SetOutputToWaveFile('audio\p06.wav',$f);$s.Speak('The rate limiter uses a token bucket per API key, stored in Redis.');$s.SetOutputToNull();
$s.SetOutputToWaveFile('audio\p07.wav',$f);$s.Speak('For the news feed we fan out on write for normal users, and fan out on read for celebrities.');$s.SetOutputToNull();
$s.SetOutputToWaveFile('audio\p08.wav',$f);$s.Speak('Postgres is the source of truth, and Elasticsearch handles full text search.');$s.SetOutputToNull();
$s.SetOutputToWaveFile('audio\p09.wav',$f);$s.Speak('We replicate across availability zones and use a circuit breaker to avoid cascading failures.');$s.SetOutputToNull();
$s.SetOutputToWaveFile('audio\p10.wav',$f);$s.Speak('The URL shortener generates a base sixty two code from an auto incrementing counter.');$s.SetOutputToNull();
$s.SetOutputToWaveFile('audio\p11.wav',$f);$s.Speak('WebSockets keep a persistent connection so the server can push messages with low latency.');$s.SetOutputToNull();
