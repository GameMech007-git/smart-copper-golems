package com.anantaya.smartcgolem;

import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SmartGolem implements ModInitializer {
	public static final String MOD_ID = "smart-golem";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {

		LOGGER.info("Copper Golems are smart now!");
	}
}