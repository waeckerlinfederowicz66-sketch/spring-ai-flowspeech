package io.flowspeech.springai.sample;

import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.ai.audio.tts.TextToSpeechModel;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class FlowSpeechTtsDemoApplication {

	public static void main(String[] args) {
		new SpringApplicationBuilder(FlowSpeechTtsDemoApplication.class).run(args);
	}

	@Bean
	CommandLineRunner synthesize(TextToSpeechModel textToSpeechModel) {
		return args -> {
			String text = args.length > 0 ? String.join(" ", args)
					: "FlowSpeech is now available through Spring AI text to speech.";
			byte[] audio = textToSpeechModel.call(text);
			Path output = Path.of("target", "flowspeech-demo.wav");
			Files.createDirectories(output.getParent());
			Files.write(output, audio);
			System.out.println("Saved audio to " + output.toAbsolutePath());
		};
	}

}
