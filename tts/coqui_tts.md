# Coqui TTS

## 基础知识

### max_decoder_steps 是什么？

max_decoder_steps 是在语音合成（TTS，Text-to-Speech）模型中使用的一个参数，主要用于**限制解码器生成音频时的最大步骤数**。

在语音合成过程中，**解码器会逐步生成语音帧，直到完成整个音频输出或达到一个最大步骤数**。如果 max_decoder_steps 设置得太小，解码器可能会在生成完音频之前就被停止，导致生成的语音不完整或中途终止。相反，如果设置得过大，则可能导致解码时间延长。

max_decoder_steps 是控制语音生成过程中解码器生成步骤数的参数。通过合理设置它，可以平衡生成速度和语音质量，避免解码过程中产生过多步骤而导致的性能问题或解码失败。

## 学习资料

- [Coqui TTS 学习资料](https://cto.eguidedog.net/node/1388)

## Issues

### [Bug in token of Chinese](https://github.com/coqui-ai/TTS/issues/2482)

使用模型 `tts_models/zh-CN/baker/tacotron2-DDC-GST`

#### 问题现象

不过不知道为什么声音后面会多了一段奇怪的重复语音(似乎是必须补齐12.05秒). 18s 会补齐到 24s, 并且为了补齐可能会丢失最后的几个字的发音.

#### 问题分析

1. 当一句话没有有`。；`这些中断符号作为一句话的结束，会强制补齐12s。
2. 当一句文字很长(长的定义是没有以`。；`这些中断符号作为一句话的结束，而是用`,、`这些符号作为过渡)，会命中如下的异常`Decoder stopped with max_decoder_steps 500`。导致只保留12s数据，而丢弃后续的内容.

#### 解决方案

1. 给每句话的末尾补齐一个符号标识为结果
2. 调整配置文件，增大steps等参数
   1. 修改 tts_models--zh-CN--baker--tacotron2-DDC-GST/config.json 里的 `max_decoder_steps`的变量

### [Does not support mixed pronunciation of Chinese and English](https://github.com/coqui-ai/TTS/issues/3825)

使用模型 `tts_models/zh-CN/baker/tacotron2-DDC-GST`

#### 问题现象

- ai、an、ang等字前多数会额外增加一个g音
- gai、gan等字前少了一个g音。有可能学错了
- e读音不准，要么读不出，要么前面加了k音

#### 问题分析

1. 后台日志打印`Character 'g' not found in the vocabulary. Discarding it.`模型不能识别这些字符

#### 解决方案

 使用阿里开源的tts做替换[cosy voice](https://github.com/FunAudioLLM/CosyVoice)
