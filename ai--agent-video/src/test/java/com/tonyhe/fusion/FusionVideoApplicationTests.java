package com.tonyhe.fusion;

import com.tonyhe.fusion.mapper.media.MediaAccessMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class FusionVideoApplicationTests {

	@Autowired
	private MediaAccessMapper mediaAccessMapper;

	@Test
	void contextLoads() {
		assertThat(mediaAccessMapper.findGrantsByUrl("/media/__permission_smoke_test__/missing.bin"))
				.isEmpty();
	}

}
